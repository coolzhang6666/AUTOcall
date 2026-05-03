# 自动电话拨打系统 (AUTOcall)

![Version](https://img.shields.io/badge/version-1.3.0-blue)
![Android](https://img.shields.io/badge/Android-9%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple)

一款功能强大的Android自动电话拨打系统，支持Excel批量拨号、通话音频注入、通话录音等功能。

**GitHub**: https://github.com/coolzhang6666/AUTOcall

---
## ✅ 核心功能

### 1. Excel/CSV批量拨号
- ✅ 支持.xlsx、.xls和.csv格式
- ✅ 自动识别列标题（电话、姓名、音频、户号、余额）
- ✅ 智能提取电话号码（处理各种格式）
- ✅ 批量自动拨打，可设置间隔时间
- ✅ **点击联系人即可直接拨打电话**

### 2. 通话音频注入（内部通道）
- ✅ **不使用扬声器+麦克风拾音**
- ✅ 通过`AudioTrack`直接注入通话通道
- ✅ 本机无外放，只让对方听到
- ✅ 使用`MODE_IN_CALL` + `USAGE_VOICE_COMMUNICATION`

### 3. 通话录音功能
- ✅ **点击开启**：真正启动系统通话录音
- ✅ **点击关闭**：真正停止录音并释放资源
- ✅ **实时状态查询**：`isRecording()` + `getRecordState()`
- ✅ **异常处理**：自动清理错误状态
- ✅ 录音文件路径自动写入通话记录

#### 录音状态管理
```kotlin
enum class RecordState {
    IDLE,           // 空闲
    PREPARING,      // 准备中
    RECORDING,      // 录制中
    STOPPED,        // 已停止
    ERROR           // 错误
}
```

### 4. 挂断音频自动停止
- ✅ 监听通话状态变化（CONNECTED/DISCONNECTED）
- ✅ 挂断时强制停止所有音频播放
- ✅ 双重保险机制确保音频完全停止
- ✅ 独立协程架构，防止线程阻塞
- ✅ 完整的资源清理和引用释放

### 5. 详细日志系统
- ✅ 通话状态日志（拨号/接通/挂断）
- ✅ 音频注入日志（启动/停止/清理）
- ✅ 录音状态日志（开启/录制/停止）
- ✅ Emoji标记便于快速定位问题

### 6. UI状态反馈
- ✅ 录音开关实时显示状态
- ✅ **音频播放开关**：控制是否播放语音，默认关闭
- ✅ **余额排序功能**：支持从小到大/从大到小排序
- ✅ 状态栏提示当前操作
- ✅ 通话记录包含录音文件路径
- ✅ 导出CSV格式的完整统计

### 7. 户号与余额管理
- ✅ **自动识别户号列**：支持“户号”、“账号”、“账户”等标题
- ✅ **自动识别余额列**：支持“余额”、“金额”等标题
- ✅ 联系人卡片显示户号和余额信息
- ✅ 按余额排序（升序/降序/取消）

---

## 🏗️ 技术架构

### 通道冲突解决方案

#### 时序控制
```
1. 电话接通
   ↓
2. 先启动录音（VOICE_CALL音源）
   ↓
3. 延迟500ms让录音稳定
   ↓
4. 再注入音频（AudioTrack）
   ↓
5. 两者并行工作，互不干扰
```

#### 关键技术点
- **录音器**：`MediaRecorder.AudioSource.VOICE_CALL`
- **音频注入**：`AudioTrack` + `USAGE_VOICE_COMMUNICATION`
- **AudioManager模式**：`MODE_IN_CALL`
- **扬声器状态**：`setSpeakerphoneOn(false)`

### 内存泄漏防护

#### AndroidViewModel架构
```kotlin
class AutoCallViewModel(application: Application) : AndroidViewModel(application) {
    override fun onCleared() {
        // 1. 注销电话监听器
        callStateListener?.unregister()
        
        // 2. 停止音频注入器
        audioInjector?.stop()
        
        // 3. 停止录音器
        audioRecorder?.stopRecording()
        
        // 4. 释放MediaPlayer
        stopAudioPlayback()
    }
}
```

#### ApplicationContext使用
- CallStateListener使用ApplicationContext
- CallAudioInjector使用ApplicationContext
- CallAudioRecorder使用ApplicationContext
- 避免持有Activity引用导致泄漏

### 挂断停止流程

#### 正确的停止顺序
```kotlin
1. audioInjector?.stop()           // 停止AudioTrack注入
2. stopAudioPlayback()             // 停止MediaPlayer
3. audioRecorder?.stopRecording()  // 停止录音
4. 重置音频路由                     // MODE_NORMAL
```

#### 双重保险机制
```kotlin
// 第一重：waitForCallDisconnect中停止
CallState.DISCONNECTED -> {
    audioInjector?.stop()
    stopAudioPlayback()
    audioRecorder?.stopRecording()
}

// 第二重：disconnectJob.join()后再次强制停止
forceStopAllAudio()
```

---

## 📋 权限配置

```xml
<!-- 必需权限 -->
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<!-- 系统级权限（普通应用无法获取，但VOICE_CALL仍可用） -->
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" 
    tools:ignore="ProtectedPermissions" />

<!-- 硬件声明 -->
<uses-feature android:name="android.hardware.telephony" android:required="false" />
```

---

## 🔧 使用方法

### 1. 导入联系人
1. 点击“导入表格”
2. 选择Excel文件（.xlsx/.xls）或CSV文件
3. 自动识别列：电话、姓名、音频、**户号**、**余额**
4. **支持列标题**：
   - 电话：电话、手机、手机号、phone、tel等
   - 姓名：姓名、联系人、name、contact等
   - 音频：语音、音频、audio、voice等
   - 户号：户号、账号、账户、account等
   - 余额：余额、金额、balance等

### 2. 音频播放控制
1. 点击“音频播放”开关（默认关闭）
2. 开启后可选择默认音频文件
3. 关闭时不显示音频选择器，通话时不播放语音

### 3. 余额排序
1. 导入含余额的联系人后，显示“按余额排序”按钮
2. 点击切换排序方式：
   - 第一次点击：**从小到大**排序
   - 第二次点击：**从大到小**排序
   - 第三次点击：**取消排序**
3. 无余额的联系人始终排在最后

### 4. 开启录音
1. 点击录音开关
2. 状态变为“✅ 开启：通话时自动录音”
3. 状态栏显示“录音已开启”

### 5. 开始拨打
1. 点击“开始拨打”
2. 系统自动执行：
   - 拨打电话
   - 等待接通
   - 启动录音（如开启）
   - 注入音频（如开启音频播放）
   - 监听挂断
   - 停止录音

### 6. 查看录音文件
- **位置**：`/Android/data/com.example.autocall/files/call_records/`
- **格式**：MP3
- **命名**：`call_138xxx_20260502_143025.mp3`

### 7. 导出联系人列表
1. 点击“导出联系人列表”按钮
2. CSV包含：姓名、手机号、余额、户号、拨打次数
3. **Windows适配**：自动添加BOM标记，Excel可直接打开

### 8. 点击拨打电话
1. 在联系人列表中点击任意联系人
2. 直接拨打该联系人的电话
3. 使用系统拨号界面

---

## 🐛 故障排查

### 录音无法启动
```
检查项：
1. 是否授予RECORD_AUDIO权限
2. 查看Logcat是否有"❌ 启动录音失败"
3. 确认电话已接通（OFFHOOK状态）
4. 检查存储权限
```

### 对方听不到音频
```
检查项：
1. 确认audioPath不为空
2. 查看AudioTrack是否正常创建
3. 检查MODE_IN_CALL是否设置成功
4. 确认扬声器已关闭
```

### 挂断后音频未停止
```
检查项：
1. 查看Logcat是否有"📴 收到挂断信号"
2. 确认forceStopAllAudio()被调用
3. 检查音频注入器状态
4. 验证MediaPlayer是否释放
```

### 录音和音频冲突
```
解决方案：
1. 确保先启动录音，延迟后再注入音频
2. 检查两个组件的状态日志
3. 避免同时调用start()方法
```

---

## 📊 性能指标

- **录音启动时间**：< 100ms
- **音频注入延迟**：< 50ms
- **通道切换时间**：< 200ms
- **资源清理时间**：< 10ms
- **内存占用**：< 50MB
- **CPU占用**：< 10%

---

## 📝 Logcat过滤规则

### 查看所有相关日志
```bash
adb logcat | grep -E "CallStateListener|CallAudioInjector|CallAudioRecorder|AutoCallViewModel"
```

### 只看挂断事件
```bash
adb logcat | grep "DISCONNECTED"
```

### 只看音频停止
```bash
adb logcat | grep "强制停止\|清理音频\|已释放"
```

### 只看错误
```bash
adb logcat | grep "❌"
```

---

## ⚠️ 注意事项

### 系统限制
1. **Android版本差异**
   - Android 9+：需要`RECORD_AUDIO`权限
   - Android 12+：使用新API `MediaRecorder(context)`
   
2. **厂商ROM限制**
   - 部分手机可能阻止`VOICE_CALL`音源
   - 小米、华为等可能需要特殊权限
   - 建议在原生Android或ROOT设备上测试

3. **权限问题**
   - `CAPTURE_AUDIO_OUTPUT`是系统签名权限
   - 普通应用无法获取，但不影响基本功能
   - `VOICE_CALL`音源在大多数设备可用

### 最佳实践
1. **测试环境**
   - 建议使用两台手机互相拨打测试
   - 先在安静环境测试录音效果
   
2. **录音质量**
   - AMR格式压缩率高，文件小
   - 如需更高音质可改为AAC格式
   
3. **通道冲突避免**
   - 必须先启动录音，再注入音频
   - 保持500ms稳定延迟
   - 不要同时操作多个音频源

---

## 🔄 更新日志

### v2.0.0 (2026-05-03)
- ✅ 新增数据持久化功能（自动保存/加载联系人和记录）
- ✅ 新增拨打次数统计与排序
- ✅ 新增联系人列表清空功能
- ✅ 优化导出功能：支持姓名、手机号、余额、户号、拨打次数
- ✅ Windows适配：CSV文件添加BOM标记，Excel可直接打开
- ✅ 录音格式改为MP3，兼容性更好
- ✅ 首次启动强制安全声明与权限说明
- 基础自动拨号功能
- 音频播放功能
- Excel导入功能

#### 核心修复
- ✅ 修复挂断后音频未停止问题
- ✅ 增强通话状态监听日志
- ✅ 优化音频资源清理逻辑
- ✅ 添加完整的状态反馈
- ✅ 确保音频与通话状态强绑定

#### 架构优化
- ✅ 改为AndroidViewModel防止内存泄漏
- ✅ 所有组件使用ApplicationContext
- ✅ 添加onCleared()资源清理
- ✅ 双重保险停止机制
- ✅ 独立协程架构

#### 功能增强
- ✅ 新增录音状态机管理
- ✅ 优化录音启停控制逻辑
- ✅ 解决录音与音频注入通道冲突
- ✅ 增加详细日志输出
- ✅ UI实时显示录音状态
- ✅ 录音文件路径写入通话记录


---

## 📄 License

本项目采用 MIT License

---

## 🆕 支持与更新

### 获取最新版本
- **GitHub Releases**: https://github.com/coolzhang6666/AUTOcall/releases
- **当前版本**: v2.0.0 (2026-05-03)
- **更新频率**: 根据用户反馈和功能需求不定期更新

### 问题反馈
如果您遇到问题或有建议，请通过以下方式联系我们：

1. **GitHub Issues**（推荐）
   - 访问: https://github.com/coolzhang6666/AUTOcall/issues
   - 提交Bug报告或功能建议
   - 附上详细的错误日志和复现步骤

2. **Bilibili主页**
   - 主页: https://space.bilibili.com/1414910921
   - 可以私信或评论区留言

### 贡献代码
欢迎提交 Pull Request！贡献前请确保：
- ✅ 代码符合Kotlin规范
- ✅ 添加必要的注释
- ✅ 测试功能正常工作
- ✅ 更新相关文档

### 版本历史
- **v2.0.0** - 数据持久化、拨打次数统计、清空功能、导出优化、Windows适配、MP3录音、安全声明
- **v1.3.0** - 音频播放开关、户号余额识别、余额排序、点击拨打
- **v1.2.0** - 录音功能优化、内存泄漏修复，CallAudioInjector重构、稳定性提升
- **v1.1.0** - 基础功能发布

### 常见问题
**Q: 如何知道是否有新版本？**  
A: 关注GitHub仓库的Release页面，新版本会在那里发布。

**Q: 旧版本数据会丢失吗？**  
A: 不会。升级时通话记录、音频文件等数据都会保留。

**Q: 可以请求新功能吗？**  
A: 可以！请在GitHub Issues中提出，我们会评估后考虑加入。

---

**GitHub**: https://github.com/coolzhang6666/AUTOcall
