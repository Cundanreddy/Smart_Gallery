// 1. Corrected the package name to match the file path
package com.example.smartgallery

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // A mutable state to hold the permission grant status
    private val isPermissionGranted = mutableStateOf(false)

    // 2. Moved registerForActivityResult out of setContent.
    // This is the correct way to register for an activity result.
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            // When the user responds to the permission dialog, this lambda is called.
            isPermissionGranted.value = isGranted
        }

    private fun askForPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermissionLauncher.launch(permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()

            // Use the state variable from the Activity
            val granted by isPermissionGranted

            // Request permission as soon as the app starts
            LaunchedEffect(Unit) {
                askForPermission()
            }

            var scanning by remember { mutableStateOf(false) }
            var results by remember { mutableStateOf(listOf<ImageGroup>()) }

            Column(modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        // The button can re-request the permission if needed
                        askForPermission()
                    }) { Text("Ask permission") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        // Only enable the scan button if permission has been granted
                        enabled = granted,
                        onClick = {
                            if (!scanning) {
                                scanning = true
                                scope.launch(Dispatchers.IO) {
                                    val scanner = MediaScanner(ctx.contentResolver)
                                    val r = scanner.scanImages { progressText ->
                                        // optional progress callback
                                    }
                                    val groups = GroupingUtils.groupByShaAndPhash(r)
                                    results = groups
                                    scanning = false
                                }
                            }
                        }) { Text("Start scan") }
                    Spacer(Modifier.width(12.dp))
                    if (scanning) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }

                Spacer(Modifier.height(12.dp))

                if (granted) {
                    LazyColumn {
                        items(results) { g ->
                            Text("Group (count=${g.assets.size}) - dup:${g.isDuplicate}")
                            Row {
                                g.assets.forEach { asset ->
                                    val thumb = asset.thumbnailBitmap
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb.asImageBitmap(), contentDescription = null,
                                            modifier = Modifier
                                                .size(64.dp)
                                                .padding(4.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                } else {
                    // Optionally, show a message if permission is not granted
                    Text("Permission is required to scan images.")
                }
            }
        }
    }
}
