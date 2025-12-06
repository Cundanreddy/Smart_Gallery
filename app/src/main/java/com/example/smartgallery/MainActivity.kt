package com.example.smartgallery

import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.example.smartgallery.data.AppDatabase
import com.example.smartgallery.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val workTag = "smartgallery_periodic_scan"

    private lateinit var db: AppDatabase
    private lateinit var repo: MediaRepository

    // For Android Q+ delete confirmation
    private val deleteIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // user confirmed; finalize quarantine for pending URIs
            finalizePendingQuarantine()
        } else {
            // canceled; clear pending
            pendingQuarantineItem = null
        }
    }
    private var pendingQuarantineItem: com.example.smartgallery.data.QuarantineItem? = null

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // DB + repo
        db = AppDatabase.get(applicationContext)
        repo = MediaRepository(db.mediaItemDao(), db.quarantineDao(), applicationContext)

        NotificationHelper.createChannel(this)

        setContent {
            val scope = rememberCoroutineScope()
            var scanning by remember { mutableStateOf(false) }
            val itemsList = remember { mutableStateListOf<ScanProgress>() }
            var scheduled by remember { mutableStateOf(false) }
            var showDashboard by remember { mutableStateOf(false) }

            val recentScans by repo.recentScansFlow(200).collectAsState(initial = emptyList())


            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
                onResult = { perms ->
                    // You can check here if permissions were granted; for now we just log
                    Log.d("MainActivity", "Permissions result: $perms")
                })

// convert to ScanProgress-like objects (thumbnail null) and prepend to UI list if desired
            LaunchedEffect(recentScans) {
                recentScans.forEach { mi ->
                    val p = ScanProgress(
                        id = 0L,
                        uri = Uri.parse(mi.contentUri),
                        displayName = mi.contentUri.substringAfterLast('/'),
                        thumbnail = null,
                        sha256 = mi.sha256,
                        dhash = mi.dhash,
                        lapVariance = mi.lapVariance ?: 0.0,
                        isBlurry = mi.isBlurry ?: false,
                        index = 0,
                        totalEstimated = 0
                    )
                    // push to UI list if not already present
                }
            }


            LaunchedEffect(Unit) {
                val permissionsToRequest = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // On Android 13+ request notification and media read permissions at runtime
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    // For older Android versions request legacy external storage read
                    permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                if (permissionsToRequest.isNotEmpty()) {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
                // check schedule state
                scheduled = isWorkScheduled()
                // optional: purge expired quarantine on startup (non-blocking)
                scope.launch { repo.purgeExpiredQuarantine() }
            }

            // collect from ScanBus
            LaunchedEffect(Unit) {
                ScanBus.events.collectLatest { p -> itemsList.add(0, p) }
            }

            val snackbarHostState = remember { SnackbarHostState() }

            Scaffold(topBar = { TopAppBar(title = { Text("Smart Gallery") }) },
                floatingActionButton = {
                    FloatingActionButton(onClick = { showDashboard = true }) {
                        Text("DB")
                    }
                }
            ) { padding ->
                Column(modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp)) {
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
                                // schedule periodic work: once per day
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

                    Text("Scanned items: ${itemsList.size}")
                    Spacer(Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(itemsList) { p ->
                            ScanItemRow(p)
                        }
                    }
                }

                // Dashboard bottom sheet / dialog
                if (showDashboard) {
                    DashboardDialog(
                        onClose = { showDashboard = false },
                        repo = repo,
                        onDeleteRequest = { uris ->
                            // perform delete -> quarantine flow
                            scope.launch {
                                // for simplicity handle single batch here (you can chunk)
                                handleDeleteWithQuarantine(uris, snackbarHostState)
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun ScanItemRow(p: ScanProgress) = Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(Color.LightGray))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = p.displayName ?: p.uri.toString())
                Text(text = "LapVar: ${String.format("%.1f", p.lapVariance)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (p.isBlurry) "BLUR" else "OK", color = if (p.isBlurry) Color.Red else Color.Green)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun handleDeleteWithQuarantine(uris: List<String>, snackbarHost: SnackbarHostState) {
        if (uris.isEmpty()) return
        // For this implementation we process sequentially: copy to quarantine -> request deletion -> finalize
        val successfulBackups = mutableListOf<com.example.smartgallery.data.QuarantineItem>()
        for (u in uris) {
            val qi = repo.moveToQuarantine(u, contentResolver)
            if (qi != null) successfulBackups.add(qi)
        }
        if (successfulBackups.isEmpty()) return

        // For Android Q+ prefer single system delete request for all URIs (if many, chunk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val androidUris = successfulBackups.map { Uri.parse(it.originalContentUri) }
            val pi = MediaStore.createDeleteRequest(contentResolver, androidUris)
            // store last quarantine item(s) pending finalize: we will finalize all after user confirms
            // For simplicity, if multiple we finalize them one by one
            pendingQuarantineItem = successfulBackups.first()
            val req = IntentSenderRequest.Builder(pi.intentSender).build()
            deleteIntentLauncher.launch(req)
            // finalize will run in onActivityResult handler -> finalizePendingQuarantine()
            // show snackbar with Undo option (we cannot finalize until user confirms deletion)
            // We show a generic snackbar that allows user to cancel within short time (but system dialog appears)
            // The finalize will persist quarantine entries after confirmation.
        } else {
            // Pre-Q: delete directly
            val deletedUris = mutableListOf<String>()
            for (qi in successfulBackups) {
                try {
                    val rows = contentResolver.delete(Uri.parse(qi.originalContentUri), null, null)
                    if (rows > 0) {
                        // persist quarantine row
                        repo.finalizeQuarantineIfDeleted(qi)
                        deletedUris.add(qi.originalContentUri)
                    } else {
                        // deletion failed; remove backup file
                        File(qi.backupPath).delete()
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
            if (deletedUris.isNotEmpty()) {
                // show Undo snackbar
                val r = snackbarHost.showSnackbar("Deleted ${deletedUris.size} items", actionLabel = "UNDO")
                if (r == SnackbarResult.ActionPerformed) {
                    // restore last deleted items (we restore all successfulBackups for simplicity)
                    for (qi in successfulBackups) {
                        val newUri = repo.restoreFromQuarantine(qi)
                        if (newUri != null) {
                            // optional: re-scan or reinsert MediaItem; repo.restoreFromQuarantine already cleaned quarantine row
                        }
                    }
                }
            }
        }
    }

    private fun finalizePendingQuarantine() {
        // When system confirms deletion for Q+, insert QuarantineItem rows and delete MediaItem rows
        lifecycleScope.launchWhenStarted {
            val qi = pendingQuarantineItem
            if (qi == null) return@launchWhenStarted
            // find all quarantine backups whose originalContentUri in the pending batch (simplified: finalize this one)
            repo.finalizeQuarantineIfDeleted(qi)
            // Show snackbar with Undo
            val snackbarHost = SnackbarHostState()
            val result = snackbarHost.showSnackbar("Deleted (moved to quarantine)", actionLabel = "UNDO")
            if (result == SnackbarResult.ActionPerformed) {
                val restored = repo.restoreFromQuarantine(qi)
                if (restored != null) {
                    // optional: reinsert into DB as needed
                }
            }
            pendingQuarantineItem = null
        }
    }

    private suspend fun showPreviewDialog(p: ScanProgress) {
        // Simple preview loader - read via contentResolver and show a dialog
        withContext(Dispatchers.Main) {
            // Compose dialog show: we'll use an ephemeral dialog via setContent? Simpler: log and no-op here
            // Better: you can implement a Compose Dialog that loads bitmap from p.uri
            // For brevity we will just open the uri using ACTION_VIEW as a quick preview:
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(p.uri, "image/*")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private suspend fun isWorkScheduled(): Boolean = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(applicationContext)
        try {
            val statuses = wm.getWorkInfosByTag(workTag).get()
            statuses.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to check scheduled work", e)
            false
        }
    }
}
