package com.example.otg_picture_reader.feature.liveview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.otg_picture_reader.data.usb.MtpImageScanner
import com.example.otg_picture_reader.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Live View 监视器的 ViewModel。
 * 管理 Live View 会话生命周期、帧数据收集、FPS 计算、参数格式化。
 */
class LiveViewViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = NikonLiveViewScanner(application)

    private val _state = MutableStateFlow(LiveViewState())
    val state: StateFlow<LiveViewState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var pendingStop = false

    /** 启动 Live View 监视器（带前置设备检查） */
    fun startLiveView() {
        if (_state.value.isActive || _state.value.isConnecting) return

        AppLog.i("LiveViewVM", "startLiveView")
        pendingStop = false

        // 前置检查：确认有相机连接
        val mtpScanner = MtpImageScanner(getApplication())
        val candidateDevice = mtpScanner.findCandidateDevice()
        if (candidateDevice == null) {
            AppLog.w("LiveViewVM", "no camera device found")
            _state.update {
                it.copy(
                    isConnecting = false,
                    errorMessage = "没有发现已连接的相机设备。\n\n请使用 USB OTG 线缆连接 Nikon 相机，\n然后重试。"
                )
            }
            return
        }
        AppLog.i("LiveViewVM", "found device: ${candidateDevice.deviceName}")

        _state.update { it.copy(isConnecting = true, errorMessage = null, frameCount = 0, fps = 0f) }

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                supervisorScope {
                    // 帧收集 + FPS 计算
                    val fpsCalculator = FpsCalculator()

                    // 启动 scanner（阻塞直到停止）
                    val scannerJob = async {
                        scanner.startLiveView()
                    }

                    // 收集帧数据
                    val collectJob = launch {
                        scanner.frames.collect { frame ->
                            withContext(Dispatchers.Main) {
                                val fps = fpsCalculator.onFrame()
                                _state.update {
                                    it.copy(
                                        isActive = true,
                                        isConnecting = false,
                                        currentFrame = frame.jpegData,
                                        frameCount = frame.index + 1,
                                        fps = fps,
                                        params = frame.params ?: it.params,
                                        errorMessage = null
                                    )
                                }
                            }
                        }
                    }

                    // 等待 scanner 结束，并通过 await 把异常交给外层 UI 错误处理。
                    scannerJob.await()
                    collectJob.cancel()
                }
                activeJob = null

            } catch (e: CancellationException) {
                AppLog.i("LiveViewVM", "live view cancelled")
            } catch (e: Throwable) {
                AppLog.e("LiveViewVM", "live view error", e)
                _state.update {
                    it.copy(
                        isActive = false,
                        isConnecting = false,
                        errorMessage = if (pendingStop) null else e.message ?: "未知错误"
                    )
                }
            } finally {
                _state.update { it.copy(isActive = false, isConnecting = false) }
                activeJob = null
                pendingStop = false
            }
        }
    }

    /** 停止 Live View 监视器 */
    fun stopLiveView() {
        AppLog.i("LiveViewVM", "stopLiveView")
        pendingStop = true
        scanner.stopLiveView()
        _state.update { it.copy(isActive = false, isConnecting = false) }
    }

    fun autofocus() {
        if (!_state.value.isActive || _state.value.isCommandBusy) return
        runLiveViewCommand("autofocus") {
            scanner.requestAutofocus()
        }
    }

    fun capture() {
        if (!_state.value.isActive || _state.value.isCommandBusy) return
        runLiveViewCommand("capture") {
            scanner.requestCapture()
        }
    }

    fun autofocusAndCapture() {
        if (!_state.value.isActive || _state.value.isCommandBusy) return
        runLiveViewCommand("autofocusAndCapture") {
            scanner.requestAutofocusAndCapture()
        }
    }

    /** 清除错误信息 */
    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveView()
    }

    private fun runLiveViewCommand(name: String, command: () -> Unit) {
        AppLog.i("LiveViewVM", name)
        _state.update { it.copy(isCommandBusy = true) }
        command()
        viewModelScope.launch {
            delay(COMMAND_BUSY_MS)
            _state.update { it.copy(isCommandBusy = false) }
        }
    }

    /**
     * 将原始 PTP 属性值格式化为可读字符串。
     */
    fun formatParams(params: LiveViewParams?): FormattedParams {
        if (params == null) return FormattedParams()

        return FormattedParams(
            shutterSpeed = formatShutterSpeed(params.shutterSpeed),
            aperture = formatAperture(params.aperture),
            iso = if (params.iso > 0) "ISO ${params.iso}" else "--",
            exposureComp = formatExposureComp(params.exposureCompensation),
            shootingMode = formatShootingMode(params.shootingMode),
            whiteBalance = formatWhiteBalance(params.whiteBalance),
            battery = if (params.batteryLevel in 1..100) "${params.batteryLevel}%" else "--",
            remainingShots = if (params.remainingShots in 0..9999) "${params.remainingShots}张" else "--"
        )
    }

    // ---- 格式化辅助 ----

    private fun formatShutterSpeed(raw: Long): String {
        if (raw == 0L) return "--"
        if (raw == 0xFFFFFFFFL) return "Bulb"
        if (raw == 0xFFFFFFFEL) return "x 200"
        val numerator = raw shr 16
        val denominator = raw and 0xFFFF
        if (numerator <= 0L || denominator <= 0L) return "0x${raw.toString(16)}"
        return if (denominator == 1L) {
            "${numerator}s"
        } else {
            "${numerator}/${denominator}"
        }
    }

    private fun formatAperture(raw: Long): String {
        if (raw == 0L) return "--"
        // Nikon 光圈值编码：实际光圈 = raw / 100（但不同机型有差异）
        val f = raw.toDouble() / 100.0
        return "f/${String.format("%.1f", f)}"
    }

    private fun formatExposureComp(raw: Long): String {
        if (raw == 0L) return "±0EV"
        val signed = raw.toShort().toInt()
        val ev = signed.toDouble() / 1000.0
        return if (ev > 0) "+${String.format("%.1f", ev)}EV" else "${String.format("%.1f", ev)}EV"
    }

    private fun formatShootingMode(raw: Long): String {
        return when (raw) {
            0x0001L -> "M"
            0x0002L -> "P"
            0x0003L -> "A"
            0x0004L -> "S"
            0x8001L -> "M"   // Nikon 可能使用不同的编码
            0x8002L -> "P"
            0x8003L -> "A"
            0x8004L -> "S"
            else -> "--"
        }
    }

    private fun formatWhiteBalance(raw: Long): String {
        return when (raw) {
            0x8001L -> "自动"
            0x8002L -> "日光"
            0x8003L -> "荧光灯"
            0x8004L -> "钨丝灯"
            0x8005L -> "闪光灯"
            0x8006L -> "阴天"
            0x8007L -> "阴影"
            0x8010L -> "预设"
            else -> "--"
        }
    }
}

private const val COMMAND_BUSY_MS = 900L

/** 简易 FPS 计算器，基于滑动窗口 */
private class FpsCalculator {
    private val windowMs = 2000L
    private val timestamps = ArrayDeque<Long>()

    fun onFrame(): Float {
        val now = System.currentTimeMillis()
        timestamps.addLast(now)
        // 清理过期时间戳
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size < 2) return 0f
        val elapsed = (now - timestamps.first()).toFloat() / 1000f
        return if (elapsed > 0f) timestamps.size.toFloat() / elapsed else 0f
    }
}
