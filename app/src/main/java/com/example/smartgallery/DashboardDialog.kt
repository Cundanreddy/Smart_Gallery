package com.example.smartgallery

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.smartgallery.data.MediaRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun DashboardDialog(
    onClose: () -> Unit,
    repo: com.example.smartgallery.data.MediaRepository,
    onDeleteRequest: suspend (List<String>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<com.example.smartgallery.data.MediaItem>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() } // contentUri -> selected
    var previewUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        repo.recentScansFlow(500).collectLatest { list ->
            items = list
            // cleanup stale selected keys
            val keys = selected.keys.toSet()
            for (k in keys) if (items.none { it.contentUri == k }) selected.remove(k)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f),
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Dashboard", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            val total = items.size
            val blurred = items.count { it.isBlurry == true }
            val withDhash = items.count { !it.dhash.isNullOrBlank() }
            Text("Total scanned: $total")
            Text("Blurred: $blurred")
            Text("With dHash: $withDhash")
            Spacer(Modifier.height(12.dp))

            Row {
                Button(onClick = { for (it in items) selected[it.contentUri] = true }) { Text("Select all") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { selected.clear() }) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onClose() }) { Text("Close") }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                // Left: list
                Column(modifier = Modifier.weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxHeight().padding(end = 8.dp)) {
                        items(items) { mi ->
                            val uri = mi.contentUri
                            val checked = selected[uri] ?: false
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .toggleable(value = checked, onValueChange = { v -> selected[uri] = v })
                                    .clickable { previewUri = uri },
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { v -> selected[uri] = v })
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(text = uri.substringAfterLast('/'))
                                    Text(text = "Blur: ${mi.isBlurry ?: false}  dHash: ${mi.dhash ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                // Right: preview panel
                Column(modifier = Modifier.width(240.dp).padding(start = 8.dp)) {
                    Text("Preview", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (previewUri != null) {
                        PreviewImage(uri = previewUri!!)
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Text("Select an item to preview", modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row {
                val anySelected = selected.any { it.value }
                Button(onClick = {
                    val toDelete = selected.filter { it.value }.map { it.key }
                    if (toDelete.isNotEmpty()) {
                        scope.launch { onDeleteRequest(toDelete) }
                    }
                }, enabled = anySelected) {
                    Text("Delete selected (Quarantine)")
                }

                Spacer(Modifier.width(8.dp))

                Button(onClick = {
                    // select blurred only
                    selected.clear()
                    for (it in items.filter { it.isBlurry == true }) selected[it.contentUri] = true
                }) {
                    Text("Select blurred")
                }
            }
        }
    }
}

@Composable
fun PreviewImage(uri: String) {
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(uri) {
        try {
            val resolver = ctx.contentResolver
            val u = Uri.parse(uri)
            resolver.openInputStream(u)?.use { stream ->
                val b = BitmapFactory.decodeStream(stream)
                bmp = b
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            bmp = null
        }
    }
    if (bmp != null) {
        Image(bmp!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(240.dp))
    } else {
        Box(modifier = Modifier.fillMaxWidth().height(240.dp).background(Color.LightGray)) {
            Text("Unable to load preview", modifier = Modifier.align(Alignment.Center))
        }
    }
}
