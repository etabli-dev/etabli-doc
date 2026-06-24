// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.doc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.raban.etabli.doc.EtabliDocApplication
import com.raban.etabli.doc.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: EtabliDocApplication) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val current by app.client.configFlow.collectAsState(initial = null)
    var url by remember(current) { mutableStateOf(current?.baseURL ?: "https://") }
    var user by remember(current) { mutableStateOf(current?.username ?: "") }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusTone by remember { mutableStateOf(StatusTone.Info) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("settings", "paperless"))

        Card(title = "server", icon = Icons.Default.Cloud) {
            MonoLabel("base URL of your Paperless-ngx instance", color = t.color.faint)
            TextInput(value = url, placeholder = "https://", onChange = { url = it })
        }
        Card(title = "user", icon = Icons.Default.Person) {
            TextInput(value = user, placeholder = "username", onChange = { user = it })
        }
        Card(title = "password", icon = Icons.Default.Lock) {
            TextInput(value = pass, placeholder = "password", onChange = { pass = it }, isSecret = true)
            MonoLabel("password is sent once to /api/token/ and discarded — only the returned token is stored.",
                      color = t.color.faint)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
            PrimaryButton(if (busy) "Connecting…" else "Connect",
                          icon = Icons.Default.CheckCircle, enabled = !busy) {
                scope.launch {
                    busy = true; status = null
                    try {
                        app.client.connect(url.trim(), user.trim(), pass)
                        pass = ""
                        status = "Connected — token stored."
                        statusTone = StatusTone.Accent
                    } catch (e: Throwable) {
                        status = e.message ?: "Failed"
                        statusTone = StatusTone.Danger
                    } finally { busy = false }
                }
            }
            if (current != null) {
                PrimaryButton("Disconnect", icon = Icons.AutoMirrored.Filled.Logout) {
                    scope.launch {
                        app.client.disconnect()
                        status = "Disconnected"
                        statusTone = StatusTone.Info
                    }
                }
            }
        }
        status?.let { StatusLabel(it, tone = statusTone) }

        Card(title = "current") {
            if (current == null) {
                MonoLabel("not connected.", color = t.color.faint)
            } else {
                Row(horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("base URL"); MonoLabel(current!!.baseURL, color = t.color.faint)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("user"); MonoLabel(current!!.username, color = t.color.faint)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("token"); MonoLabel("✓ stored", color = t.color.accent)
                }
            }
        }
    }
}
