// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.doc.net

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// Paperless-ngx HTTP client.
// Auth: POST /api/token/ with {username, password} to exchange for a long-lived token,
// then `Authorization: Token <token>` on each request.

data class PBConfig(val baseURL: String, val username: String, val hasToken: Boolean, val revision: Int = 0)

sealed class PBError(message: String) : RuntimeException(message) {
    object NotConfigured : PBError("Set the server, username and password in Settings.")
    class Http(val status: Int, val body: String?) : PBError("Server returned HTTP $status.")
    class Decoding(msg: String) : PBError("Couldn't decode response: $msg.")
    class Transport(msg: String) : PBError("Network error: $msg.")
}

data class PBDocument(
    val id: Int,
    val title: String,
    val created: String?,
    val added: String?,
    val originalFileName: String?,
    val tagIDs: List<Int>,
    val correspondent: Int?,
    val documentType: Int?,
)

data class PBNamed(val id: Int, val name: String)

private val Context.pbStore by preferencesDataStore(name = "pb_config")
private val KEY_URL   = stringPreferencesKey("baseURL")
private val KEY_USER  = stringPreferencesKey("user")
private val KEY_TOKEN = stringPreferencesKey("token")
private val KEY_ENCRYPTED_TOKEN = stringPreferencesKey("encryptedToken")

class PBClient internal constructor(
    private val store: DataStore<Preferences>,
    private val cipher: CredentialCipher,
    private val http: OkHttpClient = apiTransport(),
) {
    constructor(context: Context) : this(context.pbStore, CredentialCipher.android(context.packageName + ".credentials.v1"))

    // Disconnect and later connection attempts invalidate older network replies.
    private val configurationGeneration = AtomicLong()


    val configFlow: Flow<PBConfig?> = store.data.map { p ->
        val url = p[KEY_URL].orEmpty()
        val user = p[KEY_USER].orEmpty()
        val tok = (p[KEY_ENCRYPTED_TOKEN] ?: p[KEY_TOKEN]).orEmpty()
        if (url.isNotEmpty() && user.isNotEmpty() && tok.isNotEmpty())
            PBConfig(url, user, true, tok.hashCode()) else null
    }

    suspend fun connect(baseURL: String, username: String, password: String) {
        val base = serverBase(baseURL)
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter username and password" }
        val generation = configurationGeneration.incrementAndGet()
        val body = JSONObject().apply {
            put("username", username); put("password", password)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$base/api/token/")
            .post(body)
            .header("Accept", "application/json")
            .build()
        val token = fetch(req) { it.getString("token").also { value -> require(value.isNotBlank()) { "Server returned an empty token" } } }
        store.edit { p ->
            if (configurationGeneration.get() != generation) throw CancellationException("Account changed")
            p[KEY_URL] = base
            p[KEY_USER] = username
            p[KEY_ENCRYPTED_TOKEN] = cipher.encrypt(token, base)
            p.remove(KEY_TOKEN)
        }
    }

    suspend fun disconnect() {
        configurationGeneration.incrementAndGet()
        store.edit { it.clear() }
    }

    suspend fun listDocuments(page: Int = 1, query: String? = null): List<PBDocument> {
        require(page > 0) { "Page must be positive" }
        val q = StringBuilder("/api/documents/?ordering=-created&page=$page")
        if (!query.isNullOrBlank()) q.append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
        return get(q.toString()) { root ->
            val arr = root.getJSONArray("results")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PBDocument(
                    id = o.getInt("id"),
                    title = o.optString("title"),
                    created = o.takeUnless { it.isNull("created") }?.optString("created")?.ifBlank { null },
                    added = o.takeUnless { it.isNull("added") }?.optString("added")?.ifBlank { null },
                    originalFileName = o.takeUnless { it.isNull("original_file_name") }?.optString("original_file_name")?.ifBlank { null },
                    tagIDs = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                    correspondent = if (o.isNull("correspondent")) null else o.optInt("correspondent"),
                    documentType = if (o.isNull("document_type")) null else o.optInt("document_type"),
                )
            }
        }
    }

    suspend fun listTags(): List<PBNamed> = get("/api/tags/?page_size=200") { parseNamed(it) }
    suspend fun listCorrespondents(): List<PBNamed> = get("/api/correspondents/?page_size=200") { parseNamed(it) }
    suspend fun listDocumentTypes(): List<PBNamed> = get("/api/document_types/?page_size=200") { parseNamed(it) }

    private fun parseNamed(root: JSONObject): List<PBNamed> {
        val arr = root.getJSONArray("results")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PBNamed(o.getInt("id"), o.optString("name"))
        }
    }

    private suspend fun <T> get(path: String, parse: (JSONObject) -> T): T {
        val (url, _, token) = currentCreds()
        return fetch(Request.Builder().url(serverBase(url) + path)
            .header("Authorization", "Token $token").header("Accept", "application/json").build(), parse)
    }
    private suspend fun <T> fetch(request: Request, parse: (JSONObject) -> T): T {
        try { return parse(http.json(request)) }
        catch (e: CancellationException) { throw e }
        catch (e: HttpFailure) { throw PBError.Http(e.status, null) }
        catch (e: ResponseFailure) { throw PBError.Decoding(e.message ?: "Invalid response") }
        catch (e: JSONException) { throw PBError.Decoding(e.message ?: "Missing response fields") }
        catch (e: Exception) { throw PBError.Transport(e.message ?: "Request failed") }
    }

    private suspend fun currentCreds(): Triple<String, String, String> {
        var tok = ""
        val p = store.edit { prefs ->
            tok = cipher.readAndMigrate(prefs, KEY_TOKEN, KEY_ENCRYPTED_TOKEN, prefs[KEY_URL].orEmpty())
        }
        val url = p[KEY_URL].orEmpty()
        val user = p[KEY_USER].orEmpty()
        if (url.isEmpty() || tok.isEmpty()) throw PBError.NotConfigured
        return Triple(url, user, tok)
    }
}
