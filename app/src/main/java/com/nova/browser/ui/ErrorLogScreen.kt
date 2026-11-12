package com.nova.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nova.browser.data.ErrorLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(ErrorLog.read(context)) }
    var status by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Error log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(
                        onClick = {
                            val text = entries.joinToString("\n---\n")
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("NOVA Browser error log", text))
                            status = "Copied — paste it in a message to share it"
                        },
                        enabled = entries.isNotEmpty()
                    ) { Text("Copy") }
                    TextButton(onClick = {
                        ErrorLog.clear(context)
                        entries = emptyList()
                        status = "Log cleared"
                    }) { Text("Clear") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                "Every crash and failed operation is recorded here with its time, so a problem can be reported with the exact error.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No errors recorded.") }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(entries, key = { index, _ -> index }) { _, e ->
                        Card(
                            Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(e, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
