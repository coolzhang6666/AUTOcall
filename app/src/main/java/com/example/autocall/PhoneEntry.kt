package com.example.autocall

/**
 * 电话条目数据模型
 * @param phoneNumber 电话号码
 * @param contactName 联系人姓名（可选）
 * @param audioFilePath 语音文件路径（可选，为空则不播放）
 * @param accountNumber 户号（可选）
 * @param balance 余额信息（可选）
 */
data class PhoneEntry(
    val phoneNumber: String,
    val contactName: String = "",
    val audioFilePath: String? = null,
    val accountNumber: String? = null,
    val balance: String? = null,
    val isCalled: Boolean = false,  // 是否已拨打
    val callCount: Int = 0  // 拨打次数
)

/**
 * 通话记录数据模型
 * @param phoneNumber 电话号码
 * @param contactName 联系人姓名
 * @param callStatus 通话状态（成功/失败/未接通）
 * @param callDuration 通话时长（秒）
 * @param timestamp 通话时间戳
 * @param recordFilePath 录音文件路径（可选）
 */
data class CallRecord(
    val phoneNumber: String,
    val contactName: String,
    val callStatus: String,
    val callDuration: Long,
    val timestamp: String,
    val recordFilePath: String? = null
)
