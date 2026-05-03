package com.example.autocall

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencsv.CSVReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AutoCallViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "AutoCallViewModel"

    private val _phoneList = MutableStateFlow<List<PhoneEntry>>(emptyList())
    val phoneList: StateFlow<List<PhoneEntry>> = _phoneList

    private val _currentStatus = MutableStateFlow("准备就绪")
    val currentStatus: StateFlow<String> = _currentStatus

    fun updateStatus(status: String) {
        _currentStatus.value = status
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private var audioDirectory: File? = null

    private val sampleAudios = listOf("yu_peng_buzu.wav", "ding.wav", "no.wav")

    private val _userAudios = MutableStateFlow<List<String>>(emptyList())

    private val _selectedAudioIndex = MutableStateFlow(0)
    val selectedAudioIndex: StateFlow<Int> = _selectedAudioIndex

    private val _callRecords = MutableStateFlow<List<CallRecord>>(emptyList())

    private val _statistics = MutableStateFlow("")
    val statistics: StateFlow<String> = _statistics

    private var callStateListener: CallStateListener? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var audioInjector: CallAudioInjector? = null
    private var audioRecorder: CallAudioRecorder? = null

    private val _isRecordingEnabled = MutableStateFlow(false)
    val isRecordingEnabled: StateFlow<Boolean> = _isRecordingEnabled

    private val _isAudioPlaybackEnabled = MutableStateFlow(false)
    val isAudioPlaybackEnabled: StateFlow<Boolean> = _isAudioPlaybackEnabled

    private val _sortByBalance = MutableStateFlow(1) // 0: 不排序, 1: 从小到大, 2: 从大到小
    val sortByBalance: StateFlow<Int> = _sortByBalance

    private val _sortByCallCount = MutableStateFlow(1) // 0: 不排序, 1: 从小到大, 2: 从大到小
    val sortByCallCount: StateFlow<Int> = _sortByCallCount

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("app_data", Context.MODE_PRIVATE)
    }

    init {
        loadData()
    }

    fun toggleRecording() {
        val newState = !_isRecordingEnabled.value
        _isRecordingEnabled.value = newState
        if (newState) {
            _currentStatus.value = "录音已开启（将在下次通话时启动）"
        } else {
            _currentStatus.value = "录音已关闭"
            if (audioRecorder?.isRecording() == true) {
                audioRecorder?.stopRecording()
            }
        }
    }

    fun toggleAudioPlayback() {
        val newState = !_isAudioPlaybackEnabled.value
        _isAudioPlaybackEnabled.value = newState
        if (newState) {
            _currentStatus.value = "音频播放已开启（将在下次通话时播放）"
        } else {
            _currentStatus.value = "音频播放已关闭"
        }
    }

    fun toggleSortByBalance() {
        // 循环切换：0(不排序) -> 1(从小到大) -> 2(从大到小) -> 0(不排序)
        _sortByBalance.value = (_sortByBalance.value + 1) % 3
        sortPhoneList()
        when (_sortByBalance.value) {
            0 -> _currentStatus.value = "已取消余额排序"
            1 -> _currentStatus.value = "已按余额从小到大排序"
            2 -> _currentStatus.value = "已按余额从大到小排序"
        }
    }

    fun toggleSortByCallCount() {
        // 循环切换：0(不排序) -> 1(从小到大) -> 2(从大到小) -> 0(不排序)
        _sortByCallCount.value = (_sortByCallCount.value + 1) % 3
        sortPhoneList()
        when (_sortByCallCount.value) {
            0 -> _currentStatus.value = "已取消拨打次数排序"
            1 -> _currentStatus.value = "已按拨打次数从小到大排序"
            2 -> _currentStatus.value = "已按拨打次数从大到小排序"
        }
    }

    private fun sortPhoneList() {
        val currentList = _phoneList.value.toMutableList()
        
        // 先按拨打次数排序
        when (_sortByCallCount.value) {
            0 -> { /* 不排序 */ }
            1 -> {
                // 从小到大排序
                currentList.sortWith { a, b -> a.callCount.compareTo(b.callCount) }
            }
            2 -> {
                // 从大到小排序
                currentList.sortWith { a, b -> b.callCount.compareTo(a.callCount) }
            }
        }
        
        // 再按余额排序（如果拨打次数相同）
        when (_sortByBalance.value) {
            0 -> { /* 不排序 */ }
            1 -> {
                // 从小到大排序
                currentList.sortWith { a, b ->
                    val balanceA = parseBalance(a.balance)
                    val balanceB = parseBalance(b.balance)
                    balanceA.compareTo(balanceB)
                }
            }
            2 -> {
                // 从大到小排序
                currentList.sortWith { a, b ->
                    val balanceA = parseBalance(a.balance)
                    val balanceB = parseBalance(b.balance)
                    balanceB.compareTo(balanceA)
                }
            }
        }
        
        _phoneList.value = currentList
    }

    private fun parseBalance(balance: String?): Double {
        if (balance.isNullOrEmpty()) return Double.MAX_VALUE // 无余额的排到最后
        return try {
            // 提取数字部分，去除可能的单位
            val numberStr = balance.replace(Regex("[^0-9.-]"), "")
            numberStr.toDoubleOrNull() ?: Double.MAX_VALUE
        } catch (_: Exception) {
            Double.MAX_VALUE
        }
    }

    private fun markAsCalled(index: Int) {
        val currentList = _phoneList.value.toMutableList()
        if (index in currentList.indices) {
            val entry = currentList[index]
            currentList[index] = entry.copy(isCalled = true, callCount = entry.callCount + 1)
            _phoneList.value = currentList
        }
    }

    fun markAsCalledManually(phoneNumber: String) {
        val currentList = _phoneList.value.toMutableList()
        val index = currentList.indexOfFirst { it.phoneNumber == phoneNumber }
        if (index != -1) {
            val entry = currentList[index]
            currentList[index] = entry.copy(isCalled = true, callCount = entry.callCount + 1)
            _phoneList.value = currentList
            saveData()
        }
    }

    fun clearPhoneList() {
        _phoneList.value = emptyList()
        _currentStatus.value = "列表已清空"
        saveData()
    }

    fun setAudioDirectory(context: Context, directoryPath: String) {
        audioDirectory = File(directoryPath)
        if (!audioDirectory!!.exists()) {
            audioDirectory!!.mkdirs()
        }
        copySampleAudiosFromRaw(context)
    }

    private fun copySampleAudiosFromRaw(context: Context) {
        if (audioDirectory == null) return
        val rawResources = mapOf(
            "yu_peng_buzu.wav" to R.raw.yu_peng_buzu,
            "ding.wav" to R.raw.ding,
            "no.wav" to R.raw.no
        )
        rawResources.forEach { (fileName, resId) ->
            val file = File(audioDirectory, fileName)
            if (!file.exists()) {
                try {
                    context.resources.openRawResource(resId).use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "复制示例音频失败: ${e.message}")
                }
            }
        }
    }

    fun getAllAudios(): List<String> = sampleAudios + _userAudios.value

    fun selectAudio(index: Int) {
        val all = getAllAudios()
        if (index in all.indices) _selectedAudioIndex.value = index
    }

    fun getCurrentAudioName(): String {
        val all = getAllAudios()
        val idx = _selectedAudioIndex.value
        return if (idx in all.indices) all[idx] else all.firstOrNull() ?: ""
    }

    // --- 文件导入移至 IO 线程，UI 更新切回 Main ---
    fun importFromExcel(context: Context, excelFilePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var workbook: Workbook? = null
            try {
                val file = File(excelFilePath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = "Excel文件不存在" }
                    return@launch
                }
                val inputStream = FileInputStream(file)
                workbook = if (excelFilePath.endsWith(".xlsx", true)) XSSFWorkbook(inputStream)
                else WorkbookFactory.create(inputStream)

                val sheet = workbook.getSheetAt(0)
                val phoneList = mutableListOf<PhoneEntry>()
                val firstRow = sheet.getRow(0)
                var startIndex = 0
                var phoneCol = 0; var nameCol = 1; var audioCol = 2; var accountNumberCol = -1; var balanceCol = -1
                if (firstRow != null) {
                    val map = detectColumnHeaders(firstRow)
                    phoneCol = map["phone"] ?: 0
                    nameCol = map["name"] ?: 1
                    audioCol = map["audio"] ?: 2
                    accountNumberCol = map["accountNumber"] ?: -1
                    balanceCol = map["balance"] ?: -1
                    if (map.isNotEmpty()) startIndex = 1
                }
                for (i in startIndex..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val phone = extractPhoneNumber(getCellValue(row.getCell(phoneCol)))
                    if (phone.isNullOrEmpty()) continue
                    val name = getCellValue(row.getCell(nameCol))?.trim() ?: ""
                    val audioName = if (audioCol < row.physicalNumberOfCells) getCellValue(row.getCell(audioCol))?.trim() else null
                    val audioPath = if (!audioName.isNullOrEmpty()) getAudioPath(context, audioName) else null
                    val accountNumber = if (accountNumberCol >= 0 && accountNumberCol < row.physicalNumberOfCells) getCellValue(row.getCell(accountNumberCol))?.trim() else null
                    val balance = if (balanceCol >= 0 && balanceCol < row.physicalNumberOfCells) getCellValue(row.getCell(balanceCol))?.trim() else null
                    phoneList.add(PhoneEntry(phone, name, audioPath, accountNumber, balance))
                }
                workbook.close()
                inputStream.close()
                withContext(Dispatchers.Main) {
                    _phoneList.value = phoneList
                    _currentStatus.value = "成功导入 ${phoneList.size} 个电话"
                    saveData()
                }
            } catch (e: Exception) {
                Log.e(tag, "导入Excel失败: ${e.message}")
                withContext(Dispatchers.Main) { _currentStatus.value = "导入失败: ${e.message}" }
                workbook?.close()
            }
        }
    }

    fun importFromCSV(context: Context, csvFilePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(csvFilePath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = "CSV文件不存在" }
                    return@launch
                }
                val reader = CSVReader(FileReader(file))
                val allRows = reader.readAll()
                reader.close()
                if (allRows.isEmpty()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = "CSV文件为空" }
                    return@launch
                }
                val phoneList = mutableListOf<PhoneEntry>()
                val firstRow = allRows[0]
                var startIndex = 0
                var phoneCol = 0; var nameCol = 1; var audioCol = 2; var accountNumberCol = -1; var balanceCol = -1
                
                // 检测列标题
                if (firstRow.isNotEmpty()) {
                    val phoneKeywords = listOf("电话", "手机", "手机号", "phone", "tel")
                    val nameKeywords = listOf("姓名", "联系人", "name", "contact")
                    val audioKeywords = listOf("语音", "音频", "audio", "voice")
                    val accountNumberKeywords = listOf("户号", "账号", "账户", "account")
                    val balanceKeywords = listOf("余额", "金额", "balance")
                    
                    for ((index, header) in firstRow.withIndex()) {
                        val lowerHeader = header.lowercase()
                        if (phoneKeywords.any { it in lowerHeader }) phoneCol = index
                        else if (nameKeywords.any { it in lowerHeader }) nameCol = index
                        else if (audioKeywords.any { it in lowerHeader }) audioCol = index
                        else if (accountNumberKeywords.any { it in lowerHeader }) accountNumberCol = index
                        else if (balanceKeywords.any { it in lowerHeader }) balanceCol = index
                    }
                    startIndex = 1
                }
                
                for (i in startIndex until allRows.size) {
                    val row = allRows[i]
                    if (row.isNotEmpty() && row.size > phoneCol && row[phoneCol].isNotBlank()) {
                        val phone = row[phoneCol].trim()
                        val name = if (row.size > nameCol) row[nameCol].trim() else ""
                        val audioName = if (row.size > audioCol && audioCol >= 0 && row[audioCol].isNotBlank()) row[audioCol].trim() else null
                        val audioPath = audioName?.let { getAudioPath(context, it) }
                        val accountNumber = if (accountNumberCol >= 0 && row.size > accountNumberCol && row[accountNumberCol].isNotBlank()) row[accountNumberCol].trim() else null
                        val balance = if (balanceCol >= 0 && row.size > balanceCol && row[balanceCol].isNotBlank()) row[balanceCol].trim() else null
                        phoneList.add(PhoneEntry(phone, name, audioPath, accountNumber, balance))
                    }
                }
                withContext(Dispatchers.Main) {
                    _phoneList.value = phoneList
                    _currentStatus.value = "成功导入 ${phoneList.size} 个电话"
                    saveData()
                }
            } catch (e: Exception) {
                Log.e(tag, "导入CSV失败: ${e.message}")
                withContext(Dispatchers.Main) { _currentStatus.value = "导入失败: ${e.message}" }
            }
        }
    }

    private fun detectColumnHeaders(row: Row): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val phoneKeywords = listOf("电话", "手机", "手机号", "联系方式", "联系电话", "号码", "phone", "tel", "telephone", "mobile", "cell")
        val nameKeywords = listOf("姓名", "联系人", "名字", "称呼", "name", "contact", "person")
        val audioKeywords = listOf("语音", "音频", "录音", "文件", "audio", "voice", "sound", "file")
        val accountNumberKeywords = listOf("户号", "账号", "账户", "account", "account number", "user id")
        val balanceKeywords = listOf("余额", "金额", "余额(元)", "balance", "amount", "money")
        for (i in 0 until row.physicalNumberOfCells) {
            val cellValue = row.getCell(i)?.toString()?.trim()?.lowercase() ?: continue
            if (phoneKeywords.any { it in cellValue }) map["phone"] = i
            else if (nameKeywords.any { it in cellValue }) map["name"] = i
            else if (audioKeywords.any { it in cellValue }) map["audio"] = i
            else if (accountNumberKeywords.any { it in cellValue }) map["accountNumber"] = i
            else if (balanceKeywords.any { it in cellValue }) map["balance"] = i
        }
        return map
    }

    private fun extractPhoneNumber(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        var cleaned = value.replace(Regex("[^0-9+]"), "")
        if (cleaned.startsWith("+86")) cleaned = cleaned.substring(3)
        else if (cleaned.startsWith("86") && cleaned.length > 11) cleaned = cleaned.substring(2)
        else if (cleaned.startsWith("0") && cleaned.length == 12) cleaned = cleaned.substring(1)
        if (cleaned.matches(Regex("^1[3-9]\\d{9}$")) || cleaned.matches(Regex("^0\\d{2,3}\\d{7,8}$")) || cleaned.matches(Regex("^\\d{7,15}$")))
            return cleaned
        return cleaned.ifEmpty { null }
    }

    private fun getCellValue(cell: Cell?): String? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> if (DateUtil.isCellDateFormatted(cell)) cell.dateCellValue.toString()
            else cell.numericCellValue.toLong().toString()
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> cell.cellFormula
            else -> null
        }
    }

    private fun getAudioPath(context: Context, fileName: String): String? {
        audioDirectory?.let {
            val file = File(it, fileName)
            if (file.exists()) return file.absolutePath
        }
        val defaultDir = context.getExternalFilesDir(null)
        if (defaultDir != null) {
            val file = File(defaultDir, fileName)
            if (file.exists()) return file.absolutePath
        }
        return null
    }

    fun importAudioFile(context: Context, uri: Uri): Boolean {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileNameFromUri(context, uri)
                if (!isSupportedAudioFormat(fileName)) {
                    withContext(Dispatchers.Main) { _currentStatus.value = "不支持的音频格式" }
                    return@launch
                }
                if (audioDirectory == null) {
                    audioDirectory = File(context.getExternalFilesDir(null), "audio").also { it.mkdirs() }
                }
                var destFile = File(audioDirectory, fileName)
                var counter = 1
                while (destFile.exists()) {
                    val nameWithoutExt = fileName.substringBeforeLast(".")
                    val ext = fileName.substringAfterLast(".", "")
                    destFile = File(audioDirectory, "${nameWithoutExt}_$counter.$ext")
                    counter++
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                val newList = _userAudios.value.toMutableList()
                newList.add(destFile.name)
                _userAudios.value = newList
                val newIndex = sampleAudios.size + (newList.size - 1)
                withContext(Dispatchers.Main) {
                    _selectedAudioIndex.value = newIndex
                    _currentStatus.value = "音频导入成功: ${destFile.name}"
                }
            } catch (e: Exception) {
                Log.e(tag, "导入音频失败: ${e.message}")
                withContext(Dispatchers.Main) { _currentStatus.value = "导入音频失败: ${e.message}" }
            }
        }
        return true
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var fileName = "audio_${System.currentTimeMillis()}.mp3"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) fileName = cursor.getString(nameIndex)
        }
        return fileName
    }

    private fun isSupportedAudioFormat(fileName: String): Boolean {
        val exts = listOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "wma", "amr", "3gp", "opus", "webm", "mkv")
        return fileName.substringAfterLast(".", "").lowercase() in exts
    }

    // ---------- 核心拨打流程 ----------
    fun startAutoCall(context: Context) {
        if (_isRunning.value) return
        viewModelScope.launch(Dispatchers.Main) {
            _isRunning.value = true
            val list = _phoneList.value
            if (list.isEmpty()) {
                _currentStatus.value = "电话列表为空"
                _isRunning.value = false
                return@launch
            }

            // 清理旧监听器，新建并注册
            callStateListener?.close()
            callStateListener = CallStateListener(getApplication()).also { it.register() }

            val records = mutableListOf<CallRecord>()
            _currentStatus.value = "开始自动拨打，共 ${list.size} 个电话"

            for ((index, entry) in list.withIndex()) {
                if (!_isRunning.value) break
                _progress.value = index + 1
                _currentStatus.value = "正在拨打 ${index + 1}/${list.size}: ${entry.contactName.ifEmpty { entry.phoneNumber }}"

                val startTime = System.currentTimeMillis()
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(startTime))

                val audioPath = entry.audioFilePath ?: getCurrentAudioFile(getApplication())
                val callSuccess = makeCall(context, entry.phoneNumber)

                _currentStatus.value = "等待接通..."
                val connected = waitForCallConnect()

                var recordPath: String? = null
                if (connected) {
                    // 初始化音频注入器和录音器
                    if (audioInjector == null) audioInjector = CallAudioInjector(getApplication())
                    if (audioRecorder == null) audioRecorder = CallAudioRecorder(getApplication())

                    // 启动录音（如果开启）
                    if (_isRecordingEnabled.value) {
                        recordPath = audioRecorder?.startRecording(entry.phoneNumber)
                        _currentStatus.value = "录音已启动"
                        delay(500)
                    }

                    // 播放音频（如果开启且存在音频文件）
                    if (_isAudioPlaybackEnabled.value && !audioPath.isNullOrEmpty()) {
                        _currentStatus.value = "电话已接通，播放语音..."
                        val disconnectJob = viewModelScope.launch { waitForCallDisconnect() }
                        val audioJob = viewModelScope.launch { audioInjector?.injectAudioToCall(audioPath) }

                        disconnectJob.join()
                        audioJob.cancel()
                        audioInjector?.stop()
                        stopAudioPlayback()
                    } else {
                        // 不播放音频，只等待挂断
                        _currentStatus.value = "电话已接通（未播放音频）"
                        waitForCallDisconnect()
                    }

                    // 停止录音（如果开启）
                    if (_isRecordingEnabled.value && audioRecorder?.isRecording() == true) {
                        recordPath = audioRecorder?.stopRecording()
                    }
                    _currentStatus.value = "通话结束"
                } else {
                    _currentStatus.value = "未接通，跳过播放"
                }

                val status = if (connected && callSuccess) "成功" else "失败"
                records.add(CallRecord(
                    phoneNumber = entry.phoneNumber,
                    contactName = entry.contactName.ifEmpty { "未知" },
                    callStatus = status,
                    callDuration = (System.currentTimeMillis() - startTime) / 1000,
                    timestamp = timestamp,
                    recordFilePath = recordPath
                ))
                
                // 标记为已拨打
                markAsCalled(index)
                
                delay(3000)
            }

            callStateListener?.close()
            callStateListener = null
            _callRecords.value = records
            generateStatistics(records)
            _currentStatus.value = "全部拨打完成"
            _isRunning.value = false
        }
    }

    private suspend fun waitForCallConnect(): Boolean {
        val listener = callStateListener ?: return false
        val result = CompletableDeferred<Boolean>()
        val job = viewModelScope.launch {
            listener.callStateFlow.collect { state ->
                when (state) {
                    CallStateListener.CallState.CONNECTED -> if (!result.isCompleted) result.complete(true)
                    CallStateListener.CallState.DISCONNECTED -> if (!result.isCompleted) result.complete(false)
                    else -> {}
                }
            }
        }
        return try {
            withTimeoutOrNull(30_000) { result.await() } ?: false
        } finally {
            job.cancel()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun waitForCallDisconnect() {
        val listener = callStateListener ?: return
        val result = CompletableDeferred<Unit>()
        val job = viewModelScope.launch {
            listener.callStateFlow.collect { state ->
                if (state == CallStateListener.CallState.DISCONNECTED) {
                    if (!result.isCompleted) {
                        audioInjector?.stop()
                        stopAudioPlayback()
                        if (_isRecordingEnabled.value && audioRecorder?.isRecording() == true) {
                            audioRecorder?.stopRecording()
                        }
                        // 重置音频模式
                        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.mode = AudioManager.MODE_NORMAL
                        am.setSpeakerphoneOn(false)
                        result.complete(Unit)
                    }
                }
            }
        }
        try {
            withTimeoutOrNull(300_000) { result.await() }
        } finally {
            job.cancel()
        }
    }

    private fun stopAudioPlayback() {
        currentMediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        currentMediaPlayer = null
    }

    private fun generateStatistics(records: List<CallRecord>) {
        val total = records.size
        val success = records.count { it.callStatus == "成功" }
        val failed = total - success
        val totalDuration = records.sumOf { it.callDuration }
        _statistics.value = "总计: $total | 成功: $success | 失败: $failed | 总时长: ${totalDuration}秒"
    }

    fun stopAutoCall() {
        _isRunning.value = false
        audioInjector?.stop()
        audioRecorder?.release()
        _currentStatus.value = "已停止"
    }

    @SuppressLint("UseKtx")
    private fun makeCall(context: Context, phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            _currentStatus.value = "拨打失败: ${e.message}"
            false
        }
    }

    private fun getCurrentAudioFile(context: Context): String? {
        val name = getCurrentAudioName()
        if (name.isEmpty()) return null
        return getAudioPath(context, name)
    }

    fun exportCallRecordsToUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val phoneList = _phoneList.value
                if (phoneList.isEmpty()) {
                    withContext(Dispatchers.Main) { _currentStatus.value = "没有可导出的联系人" }
                    return@launch
                }
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    // Windows适配：BOM + UTF-8编码
                    os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    val writer = OutputStreamWriter(os, Charsets.UTF_8)
                    
                    // CSV头部（使用逗号分隔，Windows Excel兼容）
                    writer.write("\uFEFF") // BOM标记
                    writer.write("姓名,手机号,余额,户号,拨打次数\r\n")
                    
                    // 写入联系人数据
                    phoneList.forEach { entry ->
                        val name = escapeCsvField(entry.contactName.ifEmpty { "未知" })
                        val phone = escapeCsvField(entry.phoneNumber)
                        val balance = escapeCsvField(entry.balance ?: "")
                        val accountNumber = escapeCsvField(entry.accountNumber ?: "")
                        val callCount = entry.callCount.toString()
                        
                        writer.write("$name,$phone,$balance,$accountNumber,$callCount\r\n")
                    }
                    
                    writer.flush()
                }
                withContext(Dispatchers.Main) { 
                    _currentStatus.value = "导出成功，共 ${phoneList.size} 条记录"
                }
            } catch (e: Exception) {
                Log.e(tag, "导出失败: ${e.message}")
                withContext(Dispatchers.Main) { _currentStatus.value = "导出失败: ${e.message}" }
            }
        }
    }
    
    private fun escapeCsvField(field: String): String {
        // 如果字段包含逗号、引号或换行符，需要用引号包裹
        return if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            "\"${field.replace("\"", "\"\"")}\"" // 转义引号
        } else {
            field
        }
    }

    override fun onCleared() {
        super.onCleared()
        callStateListener?.close()
        audioInjector?.stop()
        audioRecorder?.release()
        stopAudioPlayback()
        saveData()
    }

    @SuppressLint("UseKtx")
    private fun saveData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gson = Gson()
                // 保存电话列表
                val phoneListJson = gson.toJson(_phoneList.value)
                prefs.edit().putString("phone_list", phoneListJson).apply()
                
                // 保存通话记录
                val recordsJson = gson.toJson(_callRecords.value)
                prefs.edit().putString("call_records", recordsJson).apply()
                
                // 保存统计信息
                prefs.edit().putString("statistics", _statistics.value).apply()
            } catch (e: Exception) {
                Log.e(tag, "保存数据失败: ${e.message}")
            }
        }
    }

    private fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gson = Gson()
                // 加载电话列表
                val phoneListJson = prefs.getString("phone_list", null)
                if (!phoneListJson.isNullOrEmpty()) {
                    val type = object : TypeToken<List<PhoneEntry>>() {}.type
                    val loadedList = gson.fromJson<List<PhoneEntry>>(phoneListJson, type)
                    withContext(Dispatchers.Main) {
                        _phoneList.value = loadedList
                    }
                }
                
                // 加载通话记录
                val recordsJson = prefs.getString("call_records", null)
                if (!recordsJson.isNullOrEmpty()) {
                    val type = object : TypeToken<List<CallRecord>>() {}.type
                    val loadedRecords = gson.fromJson<List<CallRecord>>(recordsJson, type)
                    withContext(Dispatchers.Main) {
                        _callRecords.value = loadedRecords
                    }
                }
                
                // 加载统计信息
                val stats = prefs.getString("statistics", "")
                if (!stats.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        _statistics.value = stats
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "加载数据失败: ${e.message}")
            }
        }
    }
}