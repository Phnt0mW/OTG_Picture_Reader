package com.example.otg_picture_reader.feature.liveview

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.example.otg_picture_reader.data.usb.MtpImageScanner
import com.example.otg_picture_reader.data.usb.UsbCameraSessionLock
import com.example.otg_picture_reader.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nikon 相机 Live View（实时取景/监视器）扫描器。
 * 通过 PTP Vendor-Extended 协议与 Nikon D7000 等机型通信，
 * 获取实时取景画面帧以及拍摄参数。
 *
 * 参照 [PtpImageScanner] 中的 PtpUsbSession 模式，但独立实现，
 * 不依赖任何已有文件的 private 类。
 */
class NikonLiveViewScanner(private val context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mtpScanner = MtpImageScanner(appContext)

    private val stopRequested = AtomicBoolean(false)
    private val frameChannel = Channel<LiveViewFrame>(BUFFERED)
    private val commandChannel = Channel<LiveViewCommand>(BUFFERED)
    @Volatile
    private var activeConnection: UsbDeviceConnection? = null
    @Volatile
    private var forceCloseOnStop = false

    /** 暴露帧数据流，由 ViewModel 收集 */
    val frames: Flow<LiveViewFrame> = frameChannel.receiveAsFlow()

    /**
     * 启动 Live View。
     * 阻塞调用方协程直到 [stopLiveView] 被调用或会话出错。
     */
    suspend fun startLiveView() {
        stopRequested.set(false)
        forceCloseOnStop = false
        drainStaleFrames()
        drainStaleCommands()

        UsbCameraSessionLock.mutex.withLock {
            withContext(Dispatchers.IO) {
                val device = mtpScanner.findCandidateDevice()
                    ?: throw IllegalStateException("没有发现已连接的 PTP 相机设备")

                AppLog.i("LiveView", "startLiveView device=${device.deviceName}")

                if (!usbManager.hasPermission(device)) {
                    throw SecurityException("还没有 USB 设备访问权限")
                }

                val usbInterface = findPtpInterface(device)
                    ?: throw IllegalStateException("设备没有标准 PTP 接口")
                val endpoints = findEndpoints(usbInterface)
                AppLog.i(
                    "LiveView",
                    "interface id=${usbInterface.id} endpoints bulkIn=${endpoints.bulkIn.address} " +
                        "bulkOut=${endpoints.bulkOut.address} interruptIn=${endpoints.interruptIn?.address}"
                )

                val connection = usbManager.openDevice(device)
                    ?: throw IllegalStateException("无法打开 USB 设备连接")
                activeConnection = connection
                AppLog.i("LiveView", "openDevice success fileDescriptor=${connection.fileDescriptor}")

                // 使用 var 以便 catch/finally 中复用 session 做清理
                var session: LiveViewPtpSession? = null
                var liveViewEnabled = false
                var sessionOpened = false
                var cameraParams: LiveViewParams? = null

                try {
                    claimAndConfigure(connection, device, usbInterface, endpoints)
                    session = LiveViewPtpSession(connection, endpoints.bulkIn, endpoints.bulkOut, ::isStopRequested)
                    throwIfStopped()

                    // 获取设备信息，确认是 Nikon 相机
                    session.getDeviceInfo()
                    AppLog.i("LiveView", "device info retrieved")

                    // 打开 PTP 会话（自动处理残留会话 0x201E）
                    session.openSessionSafely(1)
                    sessionOpened = true
                    AppLog.i("LiveView", "session opened")
                    throwIfStopped()

                    cameraParams = readCameraParams(session)

                    throwIfStopped()

                    // 启动 Live View
                    AppLog.i("LiveView", "starting LiveView via Nikon operation 0x9201")
                    session.startLiveView()
                    liveViewEnabled = true
                    forceCloseOnStop = true
                    Thread.sleep(LIVEVIEW_START_SETTLE_MS)

                    throwIfStopped()

                    // Live View 主循环：持续拉取帧。PTP 命令也在这里串行执行，避免和取景读帧并发撞车。
                    var frameIndex = 0L

                    while (!stopRequested.get()) {
                        throwIfStopped()

                        val now = System.currentTimeMillis()
                        handlePendingCommands(session)

                        // 拉取一帧 Live View 图像
                        val jpegData = session.getLiveViewImage()

                        if (jpegData.isEmpty()) {
                            AppLog.w("LiveView", "frame not ready or empty, retrying")
                            Thread.sleep(LIVEVIEW_RETRY_DELAY_MS)
                            continue
                        }

                        // 发送帧到 Channel
                        val frame = LiveViewFrame(
                            index = frameIndex++,
                            jpegData = jpegData,
                            timestamp = now,
                            params = cameraParams
                        )
                        frameChannel.trySend(frame)
                    }

                    // 关闭 Live View
                    AppLog.i("LiveView", "disabling LiveView")
                    forceCloseOnStop = false
                    runCatching {
                        session.endLiveViewForCleanup()
                    }

                    if (sessionOpened) {
                        runCatching { session.closeSessionForCleanup() }
                            .onFailure { AppLog.w("LiveView", "closeSession after loop failed: ${it.message}") }
                    }
                    AppLog.i("LiveView", "session closed")
                } catch (e: CancellationException) {
                    AppLog.i("LiveView", "cancelled, cleaning up")
                    withContext(NonCancellable) {
                        runCatching {
                            if (liveViewEnabled) {
                                forceCloseOnStop = false
                                session?.endLiveViewForCleanup()
                            }
                            if (sessionOpened) {
                                runCatching { session?.closeSessionForCleanup() }
                                    .onFailure { AppLog.w("LiveView", "closeSession after cancellation failed: ${it.message}") }
                            }
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    AppLog.e("LiveView", "unexpected error at phase=${if (liveViewEnabled) "LV_active" else "init"}, cleaning up", e)
                    withContext(NonCancellable) {
                        runCatching {
                            if (liveViewEnabled) {
                                forceCloseOnStop = false
                                session?.endLiveViewForCleanup()
                            }
                            if (sessionOpened) {
                                runCatching { session?.closeSessionForCleanup() }
                                    .onFailure { AppLog.w("LiveView", "closeSession after error failed: ${it.message}") }
                            }
                        }
                    }
                    throw e
                } finally {
                    runCatching { connection.releaseInterface(usbInterface) }
                        .onSuccess { AppLog.i("LiveView", "releaseInterface called") }
                        .onFailure { AppLog.w("LiveView", "releaseInterface failed: ${it.message}") }
                    activeConnection = null
                    connection.close()
                    AppLog.i("LiveView", "connection closed")
                }
            }
        }
    }

    /** 停止 Live View */
    fun stopLiveView() {
        AppLog.i("LiveView", "stopLiveView requested")
        stopRequested.set(true)
        if (forceCloseOnStop && FORCE_CLOSE_ON_STOP) {
            runCatching {
                activeConnection?.close()
            }.onSuccess {
                AppLog.i("LiveView", "active connection force-closed by stop request")
            }.onFailure {
                AppLog.w("LiveView", "failed to close active connection on stop: ${it.message}")
            }
        }
    }

    fun requestAutofocus() {
        AppLog.i("LiveView", "queue autofocus command")
        commandChannel.trySend(LiveViewCommand.Autofocus)
    }

    fun requestCapture() {
        AppLog.i("LiveView", "queue capture command")
        commandChannel.trySend(LiveViewCommand.Capture)
    }

    fun requestAutofocusAndCapture() {
        AppLog.i("LiveView", "queue autofocus+capture command")
        commandChannel.trySend(LiveViewCommand.AutofocusAndCapture)
    }

    private fun isStopRequested(): Boolean = stopRequested.get()

    private fun drainStaleFrames() {
        while (frameChannel.tryReceive().isSuccess) {
            // Drain frames left by the previous session before exposing a new one.
        }
    }

    private fun drainStaleCommands() {
        while (commandChannel.tryReceive().isSuccess) {
            // Drop commands queued for a previous PTP session.
        }
    }

    private fun handlePendingCommands(session: LiveViewPtpSession) {
        var command = commandChannel.tryReceive().getOrNull()
        while (command != null) {
            runCatching {
                when (command) {
                    LiveViewCommand.Autofocus -> session.autofocus()
                    LiveViewCommand.Capture -> session.captureToMedia(autofocus = false)
                    LiveViewCommand.AutofocusAndCapture -> {
                        session.autofocus()
                        session.waitUntilReady()
                        session.captureToMedia(autofocus = false)
                    }
                }
            }.onFailure { e ->
                AppLog.w("LiveView", "command $command failed: ${e.message}")
            }
            command = commandChannel.tryReceive().getOrNull()
        }
    }

    private fun claimAndConfigure(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        usbInterface: UsbInterface,
        endpoints: LiveViewEndpoints
    ) {
        if (device.configurationCount > 0) {
            val config = device.getConfiguration(0)
            val configured = runCatching { connection.setConfiguration(config) }.getOrDefault(false)
            AppLog.i(
                "LiveView",
                "setConfiguration id=${config.id} interfaces=${config.interfaceCount} result=$configured"
            )
        }
        if (!connection.claimInterface(usbInterface, true)) {
            throw IllegalStateException("无法占用 PTP USB 接口")
        }
        AppLog.i("LiveView", "claimInterface success force=true")
        val interfaceSet = connection.setInterface(usbInterface)
        AppLog.i("LiveView", "setInterface result=$interfaceSet fileDescriptor=${connection.fileDescriptor}")

        // 清除端点残留 stall 状态，避免设备无响应
        val clearBulkIn = connection.controlTransfer(
            USB_RT_CLEAR_FEATURE,
            USB_REQ_CLEAR_FEATURE,
            USB_FEATURE_ENDPOINT_HALT,
            endpoints.bulkIn.address,
            null, 0, USB_CTRL_TIMEOUT_MS
        )
        val clearBulkOut = connection.controlTransfer(
            USB_RT_CLEAR_FEATURE,
            USB_REQ_CLEAR_FEATURE,
            USB_FEATURE_ENDPOINT_HALT,
            endpoints.bulkOut.address,
            null, 0, USB_CTRL_TIMEOUT_MS
        )
        AppLog.i(
            "LiveView",
            "clearEndpointHalt bulkIn=${endpoints.bulkIn.address} result=$clearBulkIn " +
                "bulkOut=${endpoints.bulkOut.address} result=$clearBulkOut"
        )

        // 给相机 USB 栈一点稳定时间
        Thread.sleep(USB_STABLE_DELAY_MS)

        AppLog.i("LiveView", "interface claimed and configured, endpoints cleared")
    }

    private suspend fun readCameraParams(session: LiveViewPtpSession): LiveViewParams? {
        return runCatching {
            val params = LiveViewParams(
                shutterSpeed = session.getDevicePropValue(NIKON_PROP_EXPOSURE_TIME).toLong(),
                aperture = session.getDevicePropValue(PTP_PROP_F_NUMBER).toLong(),
                iso = session.getDevicePropValue(PTP_PROP_EXPOSURE_INDEX).toInt(),
                exposureCompensation = session.getDevicePropValue(PTP_PROP_EXPOSURE_BIAS_COMPENSATION).toLong(),
                shootingMode = session.getDevicePropValue(PTP_PROP_EXPOSURE_PROGRAM_MODE).toLong(),
                whiteBalance = session.getDevicePropValue(PTP_PROP_WHITE_BALANCE).toLong(),
                batteryLevel = session.getDevicePropValue(PTP_PROP_BATTERY_LEVEL).toInt(),
                remainingShots = session.getDevicePropValue(PTP_PROP_REMAINING_SHOTS).toInt()
            )
            AppLog.i("LiveView", "camera params snapshot=$params")
            params
        }.onFailure { e ->
            AppLog.w("LiveView", "readCameraParams failed: ${e.message}")
        }.getOrNull()
    }

    private fun findPtpInterface(device: UsbDevice): UsbInterface? {
        return (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
    }

    private fun findEndpoints(usbInterface: UsbInterface): LiveViewEndpoints {
        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        var interruptIn: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            val dir = ep.direction
            val type = ep.type
            if (type == UsbConstants.USB_ENDPOINT_XFER_BULK && dir == UsbConstants.USB_DIR_IN) {
                bulkIn = ep
            } else if (type == UsbConstants.USB_ENDPOINT_XFER_BULK && dir == UsbConstants.USB_DIR_OUT) {
                bulkOut = ep
            } else if (type == UsbConstants.USB_ENDPOINT_XFER_INT && dir == UsbConstants.USB_DIR_IN) {
                interruptIn = ep
            }
        }
        return LiveViewEndpoints(
            bulkIn = bulkIn ?: throw IllegalStateException("缺少 bulk IN endpoint"),
            bulkOut = bulkOut ?: throw IllegalStateException("缺少 bulk OUT endpoint"),
            interruptIn = interruptIn
        )
    }

    private suspend fun throwIfStopped() {
        if (stopRequested.get()) {
            throw CancellationException("LiveView 已停止")
        }
    }

    companion object {
        // PTP 标准操作码
        private const val PTP_OP_GET_DEVICE_INFO = 0x1001
        private const val PTP_OP_OPEN_SESSION = 0x1002
        private const val PTP_OP_CLOSE_SESSION = 0x1003
        private const val PTP_OP_GET_DEVICE_PROP_VALUE = 0x1015
        private const val PTP_OP_SET_DEVICE_PROP_VALUE = 0x1016

        // PTP 标准属性码
        private const val PTP_PROP_BATTERY_LEVEL = 0x5001
        private const val PTP_PROP_REMAINING_SHOTS = 0x5004
        private const val PTP_PROP_WHITE_BALANCE = 0x5005
        private const val PTP_PROP_F_NUMBER = 0x5007
        private const val PTP_PROP_EXPOSURE_PROGRAM_MODE = 0x500E
        private const val PTP_PROP_EXPOSURE_INDEX = 0x500F
        private const val PTP_PROP_EXPOSURE_BIAS_COMPENSATION = 0x5010

        // Nikon 扩展属性码 (Vendor-Extended)
        private const val NIKON_PROP_EXPOSURE_TIME = 0xD100

        // Nikon Live View 操作码
        private const val NIKON_OP_GET_LIVEVIEW_IMAGE = 0x9203

        // PTP 响应
        private const val PTP_RESPONSE_OK = 0x2001

        // 协议常量
        private const val PTP_HEADER_SIZE = 12
        private const val USB_TIMEOUT_MS = 10_000
        private const val READ_CHUNK_SIZE = 16 * 1024
        private const val WRITE_RETRY_COUNT = 3
        private const val WRITE_RETRY_DELAY_MS = 120L

        // USB Control Transfer
        private const val USB_RT_CLEAR_FEATURE = 0x02  // OUT | STANDARD | ENDPOINT
        private const val USB_REQ_CLEAR_FEATURE = 0x01
        private const val USB_FEATURE_ENDPOINT_HALT = 0x00
        private const val USB_CTRL_TIMEOUT_MS = 500
        private const val USB_STABLE_DELAY_MS = 100L
        private const val LIVEVIEW_START_SETTLE_MS = 500L
        private const val LIVEVIEW_RETRY_DELAY_MS = 120L
        private const val FORCE_CLOSE_ON_STOP = false
    }
}

// region -- Data Types --

/** Live View endpoint 对 */
internal data class LiveViewEndpoints(
    val bulkIn: UsbEndpoint,
    val bulkOut: UsbEndpoint,
    val interruptIn: UsbEndpoint?
)

/** 单帧 Live View 数据 */
data class LiveViewFrame(
    val index: Long,
    val jpegData: ByteArray,
    val timestamp: Long,
    val params: LiveViewParams? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveViewFrame) return false
        return index == other.index && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        return index.hashCode()
    }
}

/** 相机实时拍摄参数 */
data class LiveViewParams(
    val shutterSpeed: Long = 0,
    val aperture: Long = 0,
    val iso: Int = 0,
    val exposureCompensation: Long = 0,
    val shootingMode: Long = 0,
    val whiteBalance: Long = 0,
    val batteryLevel: Int = 0,
    val remainingShots: Int = 0
)

private enum class LiveViewCommand {
    Autofocus,
    Capture,
    AutofocusAndCapture
}

// endregion

// region -- PTP Live View Session (参照 PtpUsbSession) --

/**
 * Nikon Live View 专用的 PTP 会话。
 * 参照 [PtpImageScanner] 中的 PtpUsbSession 实现，但独立于此文件。
 */
internal class LiveViewPtpSession(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val shouldStop: () -> Boolean = { false }
) {
    private var nextTransactionId = 1
    private var frameLogCount = 0L
    private val readBuffer = ByteArrayOutputStream()

    // ---- 基础 PTP 操作 ----

    fun getDeviceInfo() {
        val result = transact(0x1001, transactionId = 0, readTimeoutMs = INIT_USB_TIMEOUT_MS)
        checkResponse("GetDeviceInfo", result.responseCode)
    }

    fun openSession(sessionId: Int) {
        val result = transact(0x1002, sessionId, transactionId = 0)
        checkResponse("OpenSession", result.responseCode)
    }

    /**
     * 安全打开会话：如果相机返回 0x201E (SessionAlreadyOpened)，
     * 则先关闭残留会话再重试。
     */
    fun openSessionSafely(sessionId: Int) {
        val result = transact(0x1002, sessionId, transactionId = 0)
        if (result.responseCode == PTP_RC_SESSION_ALREADY_OPENED) {
            AppLog.w("LiveView", "OpenSession got 0x201E, closing stale session first")
            closeSession()
            // 关闭后重试
            val retryResult = transact(0x1002, sessionId, transactionId = 0)
            checkResponse("OpenSession (retry after close)", retryResult.responseCode)
        } else {
            checkResponse("OpenSession", result.responseCode)
        }
    }

    fun closeSession() {
        val result = transact(0x1003)
        AppLog.i("LiveView", "CloseSession response=${ptpCodeString(result.responseCode)}")
    }

    fun closeSessionForCleanup() {
        val result = transact(0x1003, ignoreStop = true)
        AppLog.i("LiveView", "CloseSession response=${ptpCodeString(result.responseCode)}")
    }

    // ---- Device Property 操作 ----

    /**
     * 设置设备属性值。
     * PTP 协议: Command(0x1016, propCode) → Data(propValue: U32) → Response
     */
    fun setDevicePropValue(propCode: Int, value: Int) {
        val dataBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        dataBuffer.putInt(value)
        val result = transactWithData(0x1016, dataBuffer.array(), propCode)
        checkResponse("SetDevicePropValue(0x${propCode.toString(16)})", result.responseCode)
        AppLog.i("LiveView", "set prop 0x${propCode.toString(16)} = $value")
    }

    fun getDevicePropValue(propCode: Int): Number {
        val result = transact(0x1015, propCode)
        if (result.responseCode != PTP_RESPONSE_OK) {
            AppLog.w(
                "LiveView",
                "GetDevicePropValue(0x${propCode.toString(16)}) response=${ptpCodeString(result.responseCode)}"
            )
            return 0
        }
        val value = result.data.toPtpScalar()
        AppLog.i(
            "LiveView",
            "prop 0x${propCode.toString(16)} raw=${value.toLong()} bytes=${result.data.toHexString(MAX_PROP_LOG_BYTES)}"
        )
        return value
    }

    /**
     * 读取设备属性值，返回原始 U32。
     */
    fun getDevicePropValueU32(propCode: Int): Long {
        val value = getDevicePropValue(propCode)
        return if (value is Long) {
            value and 0xFFFFFFFFL
        } else {
            value.toLong()
        }
    }

    // ---- Live View 操作 ----

    fun startLiveView() {
        val result = transact(0x9201)
        checkResponse("StartLiveView", result.responseCode)
    }

    fun endLiveView() {
        val result = transact(0x9202)
        checkResponse("EndLiveView", result.responseCode)
    }

    fun endLiveViewForCleanup() {
        val result = transact(0x9202, ignoreStop = true)
        checkResponse("EndLiveView", result.responseCode)
    }

    /**
     * 获取一帧 Live View 图像（Nikon 0x9203）。
     * 返回 JPEG 格式的图像数据。
     */
    fun getLiveViewImage(): ByteArray {
        val result = transact(0x9203, readTimeoutMs = LIVEVIEW_USB_TIMEOUT_MS)
        if (result.responseCode == PTP_RESPONSE_NIKON_NOT_LIVE_VIEW ||
            result.responseCode == PTP_RESPONSE_DEVICE_BUSY
        ) {
            AppLog.w("LiveView", "GetLiveViewImage not ready: ${ptpCodeString(result.responseCode)}")
            return ByteArray(0)
        }
        checkResponse("GetLiveViewImage", result.responseCode)
        return extractJpeg(result.data)
    }

    fun autofocus() {
        AppLog.i("LiveView", "autofocus via Nikon AfDrive 0x90c1")
        val result = transact(0x90C1, readTimeoutMs = USB_TIMEOUT_MS)
        checkResponse("AfDrive", result.responseCode)
        waitUntilReady()
    }

    fun captureToMedia(autofocus: Boolean) {
        val afFlag = if (autofocus) 0xFFFFFFFE.toInt() else 0xFFFFFFFF.toInt()
        AppLog.i("LiveView", "capture via Nikon InitiateCaptureRecInMedia 0x9207 autofocus=$autofocus")
        val result = transact(0x9207, afFlag, 0xFFFFFFFF.toInt(), readTimeoutMs = USB_TIMEOUT_MS)
        if (result.responseCode == PTP_RESPONSE_PARAMETER_NOT_SUPPORTED) {
            AppLog.w("LiveView", "capture 0x9207 two-param unsupported, retrying one-param form")
            val retry = transact(0x9207, 0xFFFFFFFF.toInt(), readTimeoutMs = USB_TIMEOUT_MS)
            checkResponse("InitiateCaptureRecInMedia(one-param)", retry.responseCode)
        } else {
            checkResponse("InitiateCaptureRecInMedia", result.responseCode)
        }
        waitUntilReady()
    }

    fun waitUntilReady() {
        repeat(DEVICE_READY_RETRY_COUNT) { attempt ->
            val result = transact(0x90C8, readTimeoutMs = DEVICE_READY_TIMEOUT_MS)
            if (result.responseCode == PTP_RESPONSE_OK) {
                return
            }
            AppLog.w("LiveView", "DeviceReady attempt=${attempt + 1} response=${ptpCodeString(result.responseCode)}")
            Thread.sleep(DEVICE_READY_RETRY_DELAY_MS)
        }
    }

    // ---- 底层传输 ----

    private fun transact(
        operationCode: Int,
        vararg params: Int,
        transactionId: Int? = null,
        readTimeoutMs: Int = USB_TIMEOUT_MS,
        ignoreStop: Boolean = false
    ): PtpResult {
        val txId = transactionId ?: nextTransactionId++
        logPtpTrace("cmd op=0x${operationCode.toString(16)} tx=$txId params=${params.joinToString { u32Str(it) }}")
        writeCommand(operationCode, txId, params)

        val first = readContainer("op=0x${operationCode.toString(16)} first", readTimeoutMs, ignoreStop)
        val data: ByteArray
        val response: PtpResult
        if (first.type == CONTAINER_TYPE_DATA) {
            data = first.payload
            val resp = readContainer("op=0x${operationCode.toString(16)} response", readTimeoutMs, ignoreStop)
            response = PtpResult(resp.code, data)
        } else {
            response = PtpResult(first.code, ByteArray(0))
        }

        if (operationCode == 0x9203 && response.responseCode == PTP_RESPONSE_OK && response.data.isNotEmpty()) {
            logLiveViewFrame(response.data.size)
        } else {
            AppLog.i(
                "LiveView",
                "rsp op=0x${operationCode.toString(16)} tx=$txId code=${ptpCodeString(response.responseCode)} data=${response.data.size}"
            )
        }
        return response
    }

    private fun transactWithData(operationCode: Int, data: ByteArray, vararg params: Int): PtpResult {
        val txId = nextTransactionId++
        logPtpTrace("cmd+data op=0x${operationCode.toString(16)} tx=$txId params=${params.joinToString { u32Str(it) }}")
        writeCommand(operationCode, txId, params)
        writeData(operationCode, data, txId)

        val response = readContainer("op=0x${operationCode.toString(16)} response", USB_TIMEOUT_MS)
        AppLog.i(
            "LiveView",
            "rsp op=0x${operationCode.toString(16)} tx=$txId code=${ptpCodeString(response.code)}"
        )
        return PtpResult(response.code, ByteArray(0))
    }

    private fun writeCommand(operationCode: Int, transactionId: Int, params: IntArray) {
        val length = PTP_HEADER_SIZE + params.size * 4
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length)
        buffer.putShort(CONTAINER_TYPE_COMMAND.toShort())
        buffer.putShort(operationCode.toShort())
        buffer.putInt(transactionId)
        params.forEach { buffer.putInt(it) }
        val bytes = buffer.array()

        var written = -1
        for (attempt in 1..WRITE_RETRY_COUNT) {
            written = connection.bulkTransfer(bulkOut, bytes, bytes.size, USB_TIMEOUT_MS)
            logPtpTrace("writeCommand attempt=$attempt endpoint=${bulkOut.address} bytes=${bytes.size} written=$written")
            if (written == bytes.size) break
            Thread.sleep(WRITE_RETRY_DELAY_MS)
        }
        if (written != bytes.size) {
            throw IllegalStateException("PTP 命令写入失败：expected=${bytes.size} actual=$written")
        }
    }

    private fun writeData(operationCode: Int, data: ByteArray, transactionId: Int) {
        val length = PTP_HEADER_SIZE + data.size
        val header = ByteBuffer.allocate(PTP_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(length)
        header.putShort(CONTAINER_TYPE_DATA.toShort())
        header.putShort(operationCode.toShort())
        header.putInt(transactionId)

        val fullPacket = ByteArray(PTP_HEADER_SIZE + data.size)
        System.arraycopy(header.array(), 0, fullPacket, 0, PTP_HEADER_SIZE)
        System.arraycopy(data, 0, fullPacket, PTP_HEADER_SIZE, data.size)

        var written = -1
        for (attempt in 1..WRITE_RETRY_COUNT) {
            written = connection.bulkTransfer(bulkOut, fullPacket, fullPacket.size, USB_TIMEOUT_MS)
            logPtpTrace(
                "writeData attempt=$attempt endpoint=${bulkOut.address} op=0x${operationCode.toString(16)} " +
                    "tx=$transactionId payload=${data.size} bytes=${fullPacket.size} written=$written"
            )
            if (written == fullPacket.size) break
            Thread.sleep(WRITE_RETRY_DELAY_MS)
        }
        if (written != fullPacket.size) {
            throw IllegalStateException("PTP 数据写入失败：expected=${fullPacket.size} actual=$written")
        }
    }

    private fun readContainer(label: String, timeoutMs: Int, ignoreStop: Boolean = false): PtpContainer {
        val header = readExact(PTP_HEADER_SIZE, "$label header", timeoutMs, ignoreStop)
        val hdr = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = hdr.int
        val type = hdr.short.toInt() and 0xFFFF
        val code = hdr.short.toInt() and 0xFFFF
        val tx = hdr.int
        if (length < PTP_HEADER_SIZE) {
            throw IllegalStateException("PTP 容器长度异常：$length")
        }
        val payloadLen = length - PTP_HEADER_SIZE
        val payload = if (payloadLen > 0) readExact(payloadLen, "$label payload", timeoutMs, ignoreStop) else ByteArray(0)
        logPtpTrace("readContainer $label length=$length type=$type code=0x${code.toString(16)} tx=$tx payload=$payloadLen")
        return PtpContainer(length, type, code, tx, payload)
    }

    private fun readExact(length: Int, label: String, timeoutMs: Int, ignoreStop: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream(length)
        while (out.size() < length) {
            if (!ignoreStop && shouldStop()) {
                throw CancellationException("LiveView 已停止：$label")
            }
            val fromBuffer = consumeReadBuffer(length - out.size())
            if (fromBuffer.isNotEmpty()) {
                out.write(fromBuffer)
                logPtpTrace("readExact $label buffered=${fromBuffer.size} total=${out.size()}/$length")
                continue
            }
            val remaining = length - out.size()
            val requestSize = if (remaining <= PTP_HEADER_SIZE) READ_CHUNK_SIZE else minOf(READ_CHUNK_SIZE, remaining)
            val buf = ByteArray(requestSize)
            logPtpTrace("readExact $label request=$requestSize timeout=$timeoutMs endpoint=${bulkIn.address}")
            val read = connection.bulkTransfer(bulkIn, buf, requestSize, timeoutMs)
            logPtpTrace("readExact $label read=$read total=${out.size() + read}/$length")
            if (read <= 0) {
                throw IllegalStateException("PTP 读取超时：$label read=$read")
            }
            val needed = length - out.size()
            val consumed = minOf(needed, read)
            out.write(buf, 0, consumed)
            if (read > consumed) {
                readBuffer.write(buf, consumed, read - consumed)
            }
        }
        return out.toByteArray()
    }

    private fun extractJpeg(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        val start = data.indexOfJpegStart()
        if (start < 0) {
            AppLog.w("LiveView", "live view payload has no JPEG SOI, size=${data.size}")
            return data
        }
        val end = data.indexOfJpegEnd(start + 2)
        if (start == 0 && end == data.lastIndex) return data
        val lastExclusive = if (end >= 0) end + 1 else data.size
        logPtpTrace("trimmed live view JPEG payload size=${data.size} jpeg=${lastExclusive - start}")
        return data.copyOfRange(start, lastExclusive)
    }

    private fun logLiveViewFrame(payloadBytes: Int) {
        frameLogCount += 1
        if (frameLogCount == 1L || frameLogCount % FRAME_LOG_INTERVAL == 0L) {
            AppLog.i("LiveView", "frame ok index=$frameLogCount payload=$payloadBytes")
        }
    }

    private fun ByteArray.indexOfJpegStart(): Int {
        for (i in 0 until size - 1) {
            if (this[i] == JPEG_MARKER.toByte() && this[i + 1] == JPEG_SOI.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun ByteArray.indexOfJpegEnd(fromIndex: Int): Int {
        for (i in fromIndex until size - 1) {
            if (this[i] == JPEG_MARKER.toByte() && this[i + 1] == JPEG_EOI.toByte()) {
                return i + 1
            }
        }
        return -1
    }

    private fun consumeReadBuffer(maxBytes: Int): ByteArray {
        val bytes = readBuffer.toByteArray()
        if (bytes.isEmpty()) return ByteArray(0)
        val count = minOf(maxBytes, bytes.size)
        readBuffer.reset()
        if (bytes.size > count) {
            readBuffer.write(bytes, count, bytes.size - count)
        }
        return bytes.copyOfRange(0, count)
    }

    private fun checkResponse(name: String, code: Int) {
        if (code != PTP_RESPONSE_OK) {
            throw IllegalStateException("$name 失败：response=${ptpCodeString(code)}")
        }
    }

    companion object {
        const val PTP_RESPONSE_OK = 0x2001
        const val PTP_RESPONSE_GENERAL_ERROR = 0x2002
        const val PTP_RESPONSE_SESSION_NOT_OPEN = 0x2003
        const val PTP_RESPONSE_INVALID_TRANSACTION_ID = 0x2004
        const val PTP_RESPONSE_DEVICE_PROP_NOT_SUPPORTED = 0x200A
        const val PTP_RESPONSE_PARAMETER_NOT_SUPPORTED = 0x2006
        const val PTP_RESPONSE_OPERATION_NOT_SUPPORTED = 0x2005
        const val PTP_RESPONSE_DEVICE_BUSY = 0x2019
        const val PTP_RESPONSE_NIKON_NOT_LIVE_VIEW = 0xA00B
        const val PTP_RC_SESSION_ALREADY_OPENED = 0x201E
        const val CONTAINER_TYPE_COMMAND = 1
        const val CONTAINER_TYPE_DATA = 2
        const val PTP_HEADER_SIZE = 12
        const val USB_TIMEOUT_MS = 10_000
        const val INIT_USB_TIMEOUT_MS = 4_000
        const val LIVEVIEW_USB_TIMEOUT_MS = 1_500
        const val READ_CHUNK_SIZE = 16 * 1024
        const val WRITE_RETRY_COUNT = 3
        const val WRITE_RETRY_DELAY_MS = 120L
        const val DEVICE_READY_TIMEOUT_MS = 1_500
        const val DEVICE_READY_RETRY_COUNT = 8
        const val DEVICE_READY_RETRY_DELAY_MS = 120L
        const val JPEG_MARKER = 0xFF
        const val JPEG_SOI = 0xD8
        const val JPEG_EOI = 0xD9
        const val FRAME_LOG_INTERVAL = 30
        const val MAX_PROP_LOG_BYTES = 16
        const val TRACE_PTP_IO = false
    }
}

private fun logPtpTrace(message: String) {
    if (LiveViewPtpSession.TRACE_PTP_IO) {
        AppLog.i("LiveView", message)
    }
}

private fun ptpCodeString(code: Int): String {
    val name = when (code) {
        LiveViewPtpSession.PTP_RESPONSE_OK -> "OK"
        LiveViewPtpSession.PTP_RESPONSE_GENERAL_ERROR -> "GeneralError"
        LiveViewPtpSession.PTP_RESPONSE_SESSION_NOT_OPEN -> "SessionNotOpen"
        LiveViewPtpSession.PTP_RESPONSE_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        LiveViewPtpSession.PTP_RESPONSE_PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        LiveViewPtpSession.PTP_RESPONSE_INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        LiveViewPtpSession.PTP_RESPONSE_DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        LiveViewPtpSession.PTP_RESPONSE_DEVICE_BUSY -> "DeviceBusy"
        LiveViewPtpSession.PTP_RESPONSE_NIKON_NOT_LIVE_VIEW -> "NikonNotLiveView"
        LiveViewPtpSession.PTP_RC_SESSION_ALREADY_OPENED -> "SessionAlreadyOpen"
        else -> null
    }
    return if (name == null) {
        "0x${code.toString(16)}"
    } else {
        "0x${code.toString(16)}($name)"
    }
}

private data class PtpResult(val responseCode: Int, val data: ByteArray)

private data class PtpContainer(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val payload: ByteArray
)

private fun u32Str(value: Int): String = (value.toLong() and 0xFFFFFFFFL).toString()

private fun ByteArray.toPtpScalar(): Number {
    if (isEmpty()) return 0
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return when (size) {
        1 -> this[0].toInt() and 0xFF
        2 -> buffer.short.toInt()
        4 -> buffer.int.toLong() and 0xFFFFFFFFL
        else -> buffer.int.toLong() and 0xFFFFFFFFL
    }
}

private fun ByteArray.toHexString(maxBytes: Int): String {
    return take(maxBytes).joinToString(" ") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

// endregion
