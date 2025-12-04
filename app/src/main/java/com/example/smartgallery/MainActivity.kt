package com.example.smartgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.smartgallery.ScanWorker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val workTag = "smartgallery_periodic_scan"
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationSetup.createChannel(this)

        setContent {
            var scanning by remember { mutableStateOf(false) }
            val items = remember { mutableStateListOf<ScanProgress>() }
            var scheduled by remember { mutableStateOf(isWorkScheduled()) }

            // collect from ScanBus
            LaunchedEffect(Unit) {
                ScanBus.events.collectLatest { p ->
                    // add newest on top
                    items.add(0, p)
                }
            }

            Scaffold(topBar = { TopAppBar(title = { Text("Smart Gallery") }) }) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                    Row {
                        Button(onClick = {
                            if (!scanning) {
                                ForegroundScanService.startService(applicationContext)
                                scanning = true
                            } else {
                                ForegroundScanService.stopService(applicationContext)
                                scanning = false
                            }
                        }) {
                            Text(if (scanning) "Stop scan" else "Start scan")
                        }

                        Spacer(Modifier.width(8.dp))

                        Button(onClick = {
                            if (!scheduled) {
                                // schedule periodic work: once per day (can adjust)
                                val req = PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.DAYS)
                                    .addTag(workTag)
                                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                                    .build()
                                WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                                    "smartgallery_periodic",
                                    ExistingPeriodicWorkPolicy.REPLACE,
                                    req
                                )
                                scheduled = true
                            } else {
                                WorkManager.getInstance(applicationContext).cancelAllWorkByTag(workTag)
                                scheduled = false
                            }
                        }) {
                            Text(if (scheduled) "Cancel Scheduled" else "Schedule daily scan")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text("Scanned items: ${items.size}")
                    Spacer(Modifier.height(8.dp))

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

    private fun isWorkScheduled(): Boolean {
        val wm = WorkManager.getInstance(applicationContext)
        val statuses = wm.getWorkInfosByTag(workTag).get()
        return statuses.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }
}
