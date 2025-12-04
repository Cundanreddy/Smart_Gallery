// app/src/main/java/com/smartgallery/MainActivity.kt
package com.example.smartgallery

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var scannerJob: Job? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

            var permissionGranted by remember { mutableStateOf(false) }
            val requestPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                permissionGranted = granted
            }


            LaunchedEffect(Unit) { requestPerm.launch(permission) }

            val items = remember { mutableStateListOf<ScanProgress>() }
            var scanning by remember { mutableStateOf(false) }
            var progressText by remember { mutableStateOf("Idle") }
            var totalEstimate by remember { mutableStateOf(0) }

            Scaffold(topBar = {
                TopAppBar(title = { Text("Smart Gallery — Scan") })
            }) { padding ->
                Column(modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val toggleLabel = if (scanning) "Stop scan" else "Start scan"
                        Button(onClick = {
                            if (!permissionGranted) {
                                requestPerm.launch(permission); return@Button
                            }
                            if (scannerJob?.isActive == true) {
                                // --- STOP SCAN LOGIC ---
                                scannerJob?.cancel() // This is the correct way to cancel
                                scanning = false
                                progressText = "Stopped"
                            } else {
                                // --- START SCAN LOGIC ---
                                items.clear()
                                scanning = true
                                progressText = "Starting..."

                                // Assign the launch call to your scannerJob variable
                                scannerJob = coroutineScope.launch {
                                    val scanner = MediaScanner(ctx.contentResolver)

                                    try { // Wrap the scan in a try/catch block
                                        scanner.scanImages { p ->
                                            // We don't need the manual flag anymore
                                            // if (!scannerJobActive) throw ScanCancelledException() // REMOVE THIS

                                            // Switch context to Main for UI updates
                                            withContext(Dispatchers.Main) {
                                                items.add(0, p)
                                                progressText = "Scanned ${p.index} / ${p.totalEstimated}"
                                                totalEstimate = p.totalEstimated
                                            }
                                        }
                                        // This block will only be reached if the scan completes without cancellation
                                        withContext(Dispatchers.Main) {
                                            scanning = false
                                            progressText = "Done: ${items.size} items"
                                        }
                                    } catch (e: Exception) {
                                        // Catch any exception, including CancellationException
                                        // This makes your invokeOnCompletion redundant
                                        withContext(Dispatchers.Main) {
                                            scanning = false
                                            if (e is kotlinx.coroutines.CancellationException) {
                                                progressText = "Stopped by user"
                                            } else {
                                                progressText = "Error: ${e.message}"
                                            }
                                        }
                                    }
                                }
                            }
                        }) {
                            Text(toggleLabel)
                        }

                        Spacer(Modifier.width(12.dp))
                        Text(progressText)
                        Spacer(Modifier.weight(1f))
                        Text("Total: ${items.size}")
                    }

                    Spacer(Modifier.height(12.dp))

                    // Summary / simple histogram suggestion area
                    Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Quick summary", style = MaterialTheme.typography.bodyLarge)
                            Text("Estimated total: $totalEstimate")
                            val blurredCount = items.count { it.isBlurry }
                            Text("Blurred: $blurredCount")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items) { p ->
                            Card(modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (p.thumbnail != null) {
                                        Image(bitmap = p.thumbnail.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(72.dp))
                                    } else {
                                        Box(modifier = Modifier
                                            .size(72.dp)
                                            .background(Color.LightGray))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(p.displayName ?: p.uri.lastPathSegment ?: "image", maxLines = 2)
                                        Text("dHash: ${p.dhash ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                        Text("LapVar: ${"%.1f".format(p.lapVariance)}", style = MaterialTheme.typography.bodySmall)
                                        Text("SHA: ${p.sha256?.take(12) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        val blurColor = if (p.isBlurry) Color.Red else Color(0xFF2E7D32)
                                        Text(if (p.isBlurry) "BLUR" else "OK", color = blurColor)
                                        Spacer(Modifier.height(6.dp))
                                        Text("${p.index}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class ScanCancelledException : Exception()
