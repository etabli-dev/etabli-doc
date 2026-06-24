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

@Composable
fun DocumentsScreen(app: EtabliDocApplication) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val config by app.client.configFlow.collectAsState(initial = null)
    var query by remember { mutableStateOf("") }
    var docs by remember { mutableStateOf<List<PBDocument>?>(null) }
    var tags by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        if (config == null) { docs = null; error = null; return }
        scope.launch {
            loading = true; error = null
            try {
                docs = app.client.listDocuments(query = query.ifBlank { null })
                tags = app.client.listTags().associateBy(PBNamed::id) { it.name }
            } catch (e: Throwable) {
                error = e.message; docs = null
            } finally { loading = false }
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

        when {
            config == null   -> Card(title = "not connected") {
                MonoLabel("set the server + credentials in Settings first.", color = t.color.faint)
            }
            loading          -> LoadingState("loading documents…")
            error != null    -> ErrorState("Couldn't load", detail = error, onRetry = ::load)
            docs == null     -> Spacer(Modifier.size(0.dp))
            docs!!.isEmpty() -> EmptyState("No documents match.")
            else             -> LazyColumn(verticalArrangement = Arrangement.spacedBy(t.space.sm)) {
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
