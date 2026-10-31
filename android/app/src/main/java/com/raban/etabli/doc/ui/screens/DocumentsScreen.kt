// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.doc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raban.etabli.doc.EtabliDocApplication
import com.raban.etabli.doc.net.PBDocument
import com.raban.etabli.doc.net.PBNamed
import com.raban.etabli.doc.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

@Composable
fun DocumentsScreen(app: EtabliDocApplication) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val config by app.client.configFlow.collectAsState(initial = null)
    var query by remember { mutableStateOf("") }
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
                docs = app.client.listDocuments(query = query.ifBlank { null })
                try { tags = app.client.listTags().associateBy(PBNamed::id) { it.name } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { tagWarning = "Tag names unavailable: ${e.message}" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = e.message; docs = null
            } finally { if (generation == request) loading = false }
        }
    }
    LaunchedEffect(config) { load() }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.md),
    ) {
        PromptHeader(listOf("documents", docs?.size?.toString() ?: "—"))

        if (config != null) {
            TextInput(value = query, placeholder = "search…", onChange = { query = it })
            PrimaryButton("Search", icon = Icons.Default.Refresh, onClick = ::load)
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
        MonoLabel("#${d.id} · ${(d.created ?: "—").take(10)}", color = t.color.faint)
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
