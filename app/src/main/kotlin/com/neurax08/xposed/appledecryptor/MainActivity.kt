package com.neurax08.xposed.appledecryptor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurax08.xposed.appledecryptor.download.DownloadManager
import com.neurax08.xposed.appledecryptor.download.DownloadQueueItem
import com.neurax08.xposed.appledecryptor.download.DownloadSettings
import com.neurax08.xposed.appledecryptor.download.M4aWriter
import com.neurax08.xposed.appledecryptor.ui.theme.ComposeEmptyActivityTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadSettings.ensureLoaded()
        // UI process only observes/enqueues shared DB. Downloads run in Apple Music process.
        DownloadManager.init(applicationContext, asExecutor = false)
        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DownloadManagerScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadManagerScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloadViewModel = viewModel(),
) {
    val items by viewModel.queueItems.collectAsState(initial = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Queue", "Manual", "Settings")

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> QueueTab(items, viewModel)
            1 -> ManualInputTab(viewModel)
            2 -> SettingsTab()
        }
    }
}

@Composable
fun QueueTab(
    items: List<DownloadQueueItem>,
    viewModel: DownloadViewModel,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Download Queue",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (items.any { it.status == "COMPLETED" }) {
                TextButton(onClick = { viewModel.clearCompleted() }) {
                    Text("Clear Completed")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Shared queue path: /sdcard/Music/AppleDecryptor/db/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No downloads yet.\nPlay Apple Music tracks to auto-detect, or add manually.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.adamId }) { item ->
                    DownloadItemCard(item, viewModel)
                }
            }
        }
    }
}

@Composable
fun DownloadItemCard(
    item: DownloadQueueItem,
    viewModel: DownloadViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { "Track ${item.adamId}" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (item.artist.isNotBlank()) {
                        Text(
                            text = item.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "ID: ${item.adamId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                StatusBadge(item.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (item.status) {
                "DOWNLOADING" -> {
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Segments: ${item.completedSegments}/${item.totalSegments}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                "FAILED" -> {
                    if (item.errorMessage.isNotBlank()) {
                        Text(
                            text = item.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (item.status == "QUEUED" || item.status == "FAILED" || item.status == "CANCELED") {
                    OutlinedButton(
                        onClick = { viewModel.retry(item.adamId) },
                    ) {
                        Text("Retry")
                    }
                }
                if (item.status == "DOWNLOADING") {
                    TextButton(
                        onClick = { viewModel.cancel(item.adamId) },
                    ) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (item.status == "COMPLETED" && item.filePath.isNotBlank()) {
                    Text(
                        text = item.filePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val (color, text) = when (status) {
        "QUEUED" -> MaterialTheme.colorScheme.secondary to "Queued"
        "DOWNLOADING" -> MaterialTheme.colorScheme.primary to "Downloading"
        "COMPLETED" -> MaterialTheme.colorScheme.tertiary to "Completed"
        "FAILED" -> MaterialTheme.colorScheme.error to "Failed"
        "CANCELED" -> MaterialTheme.colorScheme.outline to "Canceled"
        else -> MaterialTheme.colorScheme.outline to status
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun ManualInputTab(viewModel: DownloadViewModel) {
    var adamId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "Manual Input",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = adamId,
            onValueChange = { adamId = it },
            label = { Text("adamId (required)") },
            placeholder = { Text("e.g. 1234567890") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title (optional)") },
            placeholder = { Text("Track title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = artist,
            onValueChange = { artist = it },
            label = { Text("Artist (optional)") },
            placeholder = { Text("Artist name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (adamId.isNotBlank()) {
                    DownloadManager.enqueue(
                        adamId = adamId.trim(),
                        title = title.trim(),
                        artist = artist.trim(),
                    )
                    adamId = ""
                    title = ""
                    artist = ""
                }
            },
            enabled = adamId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add to Queue")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = {
                val lastId = AppleDecryptorModule.lastSeenAdamId
                val lastUrl = AppleDecryptorModule.lastSeenHlsUrl
                if (lastId != null) {
                    DownloadManager.enqueue(
                        adamId = lastId,
                        hlsUrl = lastUrl.orEmpty(),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Download Last Detected Track")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Note: lastSeen* is only available inside the Apple Music process. " +
                "In the module UI process this button usually has no value until you use notification/manual adamId.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SettingsTab() {
    var autoDownload by remember { mutableStateOf(DownloadSettings.isAutoDownloadEnabled()) }
    var keepSocket by remember { mutableStateOf(DownloadSettings.isKeepSocketServersEnabled()) }
    var sampleDecrypt by remember { mutableStateOf(DownloadSettings.isPreferSampleLevelDecrypt()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSwitchRow(
            title = "Auto Download",
            subtitle = "When enabled, requestAsset intercepts auto-start download for new adamId.",
            checked = autoDownload,
            onCheckedChange = {
                autoDownload = it
                DownloadSettings.setAutoDownloadEnabled(it)
            },
        )

        SettingsSwitchRow(
            title = "Keep Socket Servers",
            subtitle = "Legacy ports 20020/10020 for external tools. Disable when fully on internal pipeline.",
            checked = keepSocket,
            onCheckedChange = {
                keepSocket = it
                DownloadSettings.setKeepSocketServersEnabled(it)
            },
        )

        SettingsSwitchRow(
            title = "Sample-level Decrypt",
            subtitle = "Split fMP4 via moof/trun and decrypt each sample (matches original socket path). Fallback to segment decrypt if parse fails.",
            checked = sampleDecrypt,
            onCheckedChange = {
                sampleDecrypt = it
                DownloadSettings.setPreferSampleLevelDecrypt(it)
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Output Directory",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = M4aWriter.OUTPUT_DIR,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Shared Queue DB",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "/sdcard/Music/AppleDecryptor/db/appledecryptor_downloads.db",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "UI process and Apple Music hook process share this path so the queue is visible across processes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Format",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "ALAC in M4A only. WAV fallback is disabled for compressed ALAC to avoid corrupt files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Native Library Status (module UI process)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Available: ${AppleMusicNativeBridge.isAvailable()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Resolver: ${AppleMusicNativeBridge.resolverStatus()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Native decrypt only works inside the Apple Music process where libandroidappmusic.so is loaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Module Info",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "AppleDecryptor v1.0",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Xposed module for Apple Music audio decryption + internal M4A export",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

class DownloadViewModel : androidx.lifecycle.ViewModel() {
    val queueItems = DownloadManager.getAllItems()

    fun retry(adamId: String) = DownloadManager.retry(adamId)
    fun cancel(adamId: String) = DownloadManager.cancel(adamId)
    fun clearCompleted() = DownloadManager.clearCompleted()
}
