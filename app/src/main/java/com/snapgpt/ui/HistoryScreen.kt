package com.snapgpt.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.database.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
private fun Thumbnail(path: String?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { if (File(path).exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, contentDescription = "Screenshot thumbnail", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<SessionEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (sessions.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Clear all") } },
            )
        },
    ) { pad ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (settings.historyEnabled) "No saved scans yet. Scan from the floating circle and they appear here."
                    else "History is turned off. Enable it in Settings > Privacy.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sessions, key = { it.id }) { s ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(s.id) }, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumbnail(s.thumbnailPath, Modifier.size(64.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.createdAt)), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOf(if (s.scanType == "AREA") "Area" else "Full screen", s.summary.ifBlank { "No subject" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text("${s.totalTokens} tokens (provider-reported)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { confirmOne = s }) { Icon(Icons.Default.Delete, contentDescription = "Delete scan") }
                        }
                    }
                }
            }
        }
    }

    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("Clear all history?") }, text = { Text("All saved scans, explanations and thumbnails will be deleted from this device.") },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmAll = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
    )
    confirmOne?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmOne = null },
            title = { Text("Delete this scan?") }, text = { Text("Its questions, answers and conversation will be removed.") },
            confirmButton = { TextButton(onClick = { vm.deleteSession(s.id); confirmOne = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmOne = null }) { Text("Cancel") } },
        )
    }
}
