// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.doc.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raban.etabli.doc.EtabliDocApplication
import com.raban.etabli.doc.net.PBDocument
import com.raban.etabli.doc.net.PBNamed
import com.raban.etabli.doc.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

@Composable
fun DocumentsScreen(app: EtabliDocApplication) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val config by app.client.configFlow.collectAsState(initial = null)
    var query by remember(config) { mutableStateOf("") }
    var tagID by remember(config) { mutableStateOf<Int?>(null) }
    var correspondentID by remember(config) { mutableStateOf<Int?>(null) }
    var documentTypeID by remember(config) { mutableStateOf<Int?>(null) }
    var correspondents by remember(config) { mutableStateOf<List<PBNamed>>(emptyList()) }
    var documentTypes by remember(config) { mutableStateOf<List<PBNamed>>(emptyList()) }
    var docs by remember { mutableStateOf<List<PBDocument>?>(null) }
    var tags by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var tagWarning by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) { onDispose { loadJob?.cancel() } }

    fun load() {
        val request = ++generation
        loadJob?.cancel()
        if (config == null) { docs = null; error = null; loading = false; return }
        loadJob = scope.launch {
            loading = true; error = null; tagWarning = null; tags = emptyMap()
            try {
                val documents = app.client.listDocuments(query = query.ifBlank { null }, tagID = tagID,
                    correspondentID = correspondentID, documentTypeID = documentTypeID)
                if (generation != request) return@launch
                docs = documents
                loading = false
                val warnings = mutableListOf<String>()
                suspend fun names(label: String, fetch: suspend () -> List<PBNamed>): List<PBNamed> = try {
                    fetch()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { warnings.add(label); emptyList() }
                val (nextTags, nextCorrespondents, nextTypes) = coroutineScope {
                    val tagNames = async { names("tags", app.client::listTags) }
                    val correspondentNames = async { names("correspondents", app.client::listCorrespondents) }
                    val typeNames = async { names("document types", app.client::listDocumentTypes) }
                    Triple(tagNames.await(), correspondentNames.await(), typeNames.await())
                }
                if (generation != request) return@launch
                tags = nextTags.associateBy(PBNamed::id) { it.name }
                correspondents = nextCorrespondents
                documentTypes = nextTypes
                tagWarning = warnings.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Names unavailable: ")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == request) { error = e.message; docs = null }
            } finally { if (generation == request) loading = false }
        }
    }
    LaunchedEffect(config, tagID, correspondentID, documentTypeID) { load() }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.md),
    ) {
        PromptHeader(listOf("documents", docs?.size?.toString() ?: "—"))

        if (config != null) {
            TextInput(value = query, placeholder = "search…", onChange = { query = it })
            PrimaryButton("Search / refresh", icon = Icons.Default.Refresh, onClick = ::load)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                DocumentFilter("Tag", tags.map { PBNamed(it.key, it.value) }, tagID) { tagID = it }
                DocumentFilter("Correspondent", correspondents, correspondentID) { correspondentID = it }
                DocumentFilter("Type", documentTypes, documentTypeID) { documentTypeID = it }
                if (tagID != null || correspondentID != null || documentTypeID != null) {
                    TextButton(onClick = { tagID = null; correspondentID = null; documentTypeID = null }) { Text("Clear filters") }
                }
            }
        }

        tagWarning?.let { StatusLabel(it, tone = StatusTone.Warn) }
        when {
            config == null   -> Card(title = "not connected") {
                MonoLabel("set the server + credentials in Settings first.", color = t.color.faint)
            }
            loading          -> LoadingState("loading documents…")
            error != null    -> ErrorState("Couldn't load", detail = error, onRetry = ::load)
            docs == null     -> Spacer(Modifier.size(0.dp))
            docs!!.isEmpty() -> EmptyState("No documents match.")
            else             -> LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.space.sm)) {
                items(docs!!, key = { it.id }) { d -> DocRow(d, tags) }
                item { Spacer(Modifier.height(t.space.xl)) }
            }
        }
    }
}

@Composable
private fun DocRow(d: PBDocument, tags: Map<Int, String>) {
    val t = Coder.tokens
    Card(title = d.title.ifBlank { "(no title)" }, icon = Icons.Default.Description) {
        MonoLabel("#${d.id} · ${(d.added ?: "—").take(10)}", color = t.color.faint)
        d.originalFileName?.let { MonoLabel(it, color = t.color.faint) }
        if (d.tagIDs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.xs)) {
                d.tagIDs.take(6).forEach { tid ->
                    StatusLabel(tags[tid] ?: "#$tid", tone = StatusTone.Info)
                }
            }
        }
    }
}

@Composable
private fun DocumentFilter(label: String, options: List<PBNamed>, selected: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val name = options.firstOrNull { it.id == selected }?.name ?: selected?.let { "#$it" } ?: "all"
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label: $name") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All ${label.lowercase()}") }, onClick = { onSelect(null); expanded = false })
            options.sortedBy { it.name.lowercase() }.forEach { option ->
                DropdownMenuItem(text = { Text(option.name) }, onClick = { onSelect(option.id); expanded = false })
            }
        }
    }
}
