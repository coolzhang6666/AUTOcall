package com.example.autocall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.autocall.ui.theme.AUTOCallTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: AutoCallViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importFileFromUri(it) }
    }

    private val importAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importAudioFile(this, it)
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            viewModel.exportCallRecordsToUri(this, it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestPermissions()

        setContent {
            AUTOCallTheme {
                MainScreen(
                    viewModel = viewModel,
                    onImportFile = { openDocumentLauncher.launch("*/*") },
                    onImportAudio = { importAudioLauncher.launch("audio/*") },
                    onExport = {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLauncher.launch("call_records_$ts.csv")
                    }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun importFileFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val fileName = getFileNameFromUri(uri)
            val file = File(cacheDir, fileName)
            inputStream?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            when {
                fileName.endsWith(".csv", true) -> viewModel.importFromCSV(this, file.absolutePath)
                fileName.endsWith(".xlsx", true) || fileName.endsWith(".xls", true) ->
                    viewModel.importFromExcel(this, file.absolutePath)
                else -> viewModel.updateStatus("不支持的文件格式")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && col != -1) cursor.getString(col) else "imported_file.csv"
        } ?: "imported_file.csv"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AutoCallViewModel,
    onImportFile: () -> Unit,
    onImportAudio: () -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    val phoneList by viewModel.phoneList.collectAsState()
    val currentStatus by viewModel.currentStatus.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val selectedAudioIndex by viewModel.selectedAudioIndex.collectAsState()
    val statistics by viewModel.statistics.collectAsState()
    val callRecords by viewModel.callRecords.collectAsState()
    val isRecordingEnabled by viewModel.isRecordingEnabled.collectAsState()

    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val audioDir = File(context.getExternalFilesDir(null), "audio")
        viewModel.setAudioDirectory(context, audioDir.absolutePath)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("自动电话拨打系统") },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "关于")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(status = currentStatus, progress = progress, total = phoneList.size)

                // 修复：音频选择器现在能响应式更新
                AudioSelector(
                    viewModel = viewModel,
                    selectedIndex = selectedAudioIndex
                )

                RecordingSwitch(
                    isEnabled = isRecordingEnabled,
                    onToggle = { viewModel.toggleRecording() },
                    currentStatus = currentStatus
                )

                ControlButtons(
                    isRunning = isRunning,
                    onStart = { viewModel.startAutoCall(context) },
                    onStop = { viewModel.stopAutoCall() },
                    onImportFile = onImportFile,
                    onImportAudio = onImportAudio
                )

                Text(
                    text = "电话列表 (${phoneList.size}个)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PhoneList(phoneList = phoneList)

                if (statistics.isNotEmpty()) {
                    StatisticsCard(statistics = statistics)
                }
            }

            if (callRecords.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        ExportButton(onExport = onExport)
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于软件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("自动电话拨打系统")

                    Text(
                        text = "版本: 1.0.0",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/coolzhang6666/AUTOcall"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    )

                    Text(
                        text = "开发者: 张昊沅",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/1414910921?spm_id_from=333.1007.0.0"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    )

                    Text("\n功能说明：")
                    Text("• 支持Excel/CSV文件导入联系人")
                    Text("• 自动拨打电话并播放语音")
                    Text("• 支持通话录音")
                    Text("• 导出通话记录统计")
                }
            },
            confirmButton = {
                Button(onClick = { showAboutDialog = false }) { Text("确定") }
            }
        )
    }
}

// ---------- 各组件（均无变动，仅 AudioSelector 有优化） ----------

@Composable
fun StatusCard(status: String, progress: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(status, style = MaterialTheme.typography.bodyLarge)
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { if (total > 0) progress.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("进度: $progress / $total", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun AudioSelector(
    viewModel: AutoCallViewModel,
    selectedIndex: Int
) {
    val allAudios = viewModel.getAllAudios()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("默认音频选择", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                itemsIndexed(allAudios) { index, _ ->
                    val displayName = when (index) {
                        0 -> "余额不足"
                        1 -> "停电"
                        2 -> "欠费"
                        else -> "自定义${index - 2}"
                    }
                    FilterChip(
                        selected = selectedIndex == index,
                        onClick = { viewModel.selectAudio(index) },
                        label = { Text(displayName) }
                    )
                }
            }

            Text(
                text = "当前选中: ${allAudios.getOrElse(selectedIndex) { allAudios.firstOrNull() ?: "" }}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun StatisticsCard(statistics: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("通话统计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(statistics, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun RecordingSwitch(isEnabled: Boolean, onToggle: () -> Unit, currentStatus: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("通话录音", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (isEnabled) "✅ 开启：通话时自动录音" else "❌ 关闭：不录音",
                    style = MaterialTheme.typography.bodySmall
                )
                if (currentStatus.contains("录音", ignoreCase = true)) {
                    Text(
                        "状态: $currentStatus",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Switch(checked = isEnabled, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
fun ExportButton(onExport: () -> Unit) {
    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
        Text("导出通话记录")
    }
}

@Composable
fun ControlButtons(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onImportFile: () -> Unit,
    onImportAudio: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportFile, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                Text("导入表格")
            }
            Button(onClick = onImportAudio, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                Text("导入音频")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                Text("开始拨打")
            }
            Button(
                onClick = onStop, enabled = isRunning, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("停止")
            }
        }
    }
}

@Composable
fun PhoneList(phoneList: List<PhoneEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (phoneList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                Text("暂无电话数据")
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(phoneList) { entry ->
                    PhoneItem(entry)
                }
            }
        }
    }
}

@Composable
fun PhoneItem(entry: PhoneEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.contactName.ifEmpty { "未知联系人" },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
                )
                Text(
                    entry.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (entry.audioFilePath != null) "✓ 有语音" else "✗ 无语音",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.audioFilePath != null) Color.Green else Color.Gray
                )
            }
        }
    }
}