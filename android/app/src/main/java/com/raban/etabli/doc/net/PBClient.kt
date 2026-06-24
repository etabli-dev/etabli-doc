// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.doc.net

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Paperless-ngx HTTP client.
// Auth: POST /api/token/ with {username, password} to exchange for a long-lived token,
// then `Authorization: Token <token>` on each request.

data class PBConfig(val baseURL: String, val username: String, val hasToken: Boolean)

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

class PBClient(private val context: Context) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val configFlow: Flow<PBConfig?> = context.pbStore.data.map { p ->
        val url = p[KEY_URL].orEmpty()
        val user = p[KEY_USER].orEmpty()
        val tok = p[KEY_TOKEN].orEmpty()
        if (url.isNotEmpty() && user.isNotEmpty() && tok.isNotEmpty())
            PBConfig(url, user, true) else null
    }

    suspend fun connect(baseURL: String, username: String, password: String) {
        val base = baseURL.trimEnd('/')
        val body = JSONObject().apply {
            put("username", username); put("password", password)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$base/api/token/")
            .post(body)
            .header("Accept", "application/json")
            .build()
        val token = withContext(Dispatchers.IO) {
            try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw PBError.Http(resp.code, text)
                    try { JSONObject(text).getString("token") }
                    catch (t: Throwable) { throw PBError.Decoding(t.message ?: "?") }
                }
            } catch (e: PBError) { throw e } catch (t: Throwable) {
                throw PBError.Transport(t.message ?: "?")
            }
        }
        context.pbStore.edit { p ->
            p[KEY_URL] = base
            p[KEY_USER] = username
            p[KEY_TOKEN] = token
        }
    }

    suspend fun disconnect() { context.pbStore.edit { it.clear() } }

    suspend fun listDocuments(page: Int = 1, query: String? = null): List<PBDocument> {
        val q = StringBuilder("/api/documents/?ordering=-created&page=$page")
        if (!query.isNullOrBlank()) q.append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
        return get(q.toString()) { root ->
            val arr = root.optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PBDocument(
                    id = o.getInt("id"),
                    title = o.optString("title"),
                    created = o.optString("created").ifBlank { null },
                    added = o.optString("added").ifBlank { null },
                    originalFileName = o.optString("original_file_name").ifBlank { null },
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
        val arr = root.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PBNamed(o.getInt("id"), o.optString("name"))
        }
    }

    private suspend fun <T> get(path: String, parse: (JSONObject) -> T): T = withContext(Dispatchers.IO) {
        val (url, _, token) = currentCreds()
        val req = Request.Builder()
            .url(url + path)
            .header("Authorization", "Token $token")
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string()
                if (!resp.isSuccessful) throw PBError.Http(resp.code, text)
                try { parse(JSONObject(text.orEmpty())) }
                catch (t: Throwable) { throw PBError.Decoding(t.message ?: "?") }
            }
        } catch (e: PBError) { throw e } catch (t: Throwable) {
            throw PBError.Transport(t.message ?: "?")
        }
    }

    private suspend fun currentCreds(): Triple<String, String, String> {
        val p = context.pbStore.data.first()
        val url = p[KEY_URL].orEmpty()
        val user = p[KEY_USER].orEmpty()
        val tok = p[KEY_TOKEN].orEmpty()
        if (url.isEmpty() || tok.isEmpty()) throw PBError.NotConfigured
        return Triple(url, user, tok)
    }
}
