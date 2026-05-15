package com.example.otg_picture_reader.data.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import com.example.otg_picture_reader.data.local.ExifMetadataReader
import com.example.otg_picture_reader.data.model.ImageExifInfo
import com.example.otg_picture_reader.data.model.OtgImage
import com.example.otg_picture_reader.util.AppLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PtpImageScanner(
    context: Context
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val cacheDirectory = File(appContext.cacheDir, "ptp_images").apply { mkdirs() }

    fun scanImages(
        device: UsbDevice,
        existingImages: List<OtgImage> = emptyList(),
        onProgress: (UsbScanProgress) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): List<OtgImage> {
        AppLog.i("PTP", "scanImages start device=${device.deviceName}")
        throwIfStopped(shouldStop)
        if (!usbManager.hasPermission(device)) {
            throw SecurityException("还没有 USB 设备访问权限")
        }
        val usbInterface = findPtpInterface(device)
            ?: throw IllegalStateException("设备没有标准 PTP 接口")
        val endpoints = findEndpoints(usbInterface)
        AppLog.i(
            "PTP",
            "interface id=${usbInterface.id} endpoints bulkIn=${endpoints.bulkIn.address} " +
                "bulkOut=${endpoints.bulkOut.address} interruptIn=${endpoints.interruptIn?.address}"
        )

        val reusableImages = buildReusableImageMap(existingImages)

        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("无法打开 USB 设备连接")

        try {
            if (device.configurationCount > 0) {
                val configuration = device.getConfiguration(0)
                val configured = runCatching { connection.setConfiguration(configuration) }.getOrDefault(false)
                AppLog.i(
                    "PTP",
                    "setConfiguration id=${configuration.id} interfaces=${configuration.interfaceCount} result=$configured"
                )
            }
            if (!connection.claimInterface(usbInterface, true)) {
                throw IllegalStateException("无法占用 PTP USB 接口")
            }
            AppLog.i("PTP", "claimInterface success")
            val interfaceSet = connection.setInterface(usbInterface)
            AppLog.i("PTP", "setInterface result=$interfaceSet fileDescriptor=${connection.fileDescriptor}")

            val session = PtpUsbSession(connection, endpoints.bulkIn, endpoints.bulkOut)
            throwIfStopped(shouldStop)
            session.getDeviceInfo()
            throwIfStopped(shouldStop)
            session.openSession(1)
            throwIfStopped(shouldStop)
            val storageIds = session.getStorageIds()
            AppLog.i("PTP", "storageIds=${storageIds.joinToString { it.toUIntString() }}")

            val images = mutableListOf<OtgImage>()
            val seenImageIds = mutableSetOf<String>()
            var scannedCount = 0
            var totalCount = 0
            storageIds.forEach { storageId ->
                throwIfStopped(shouldStop)
                val handles = session.getObjectHandles(storageId)
                totalCount += handles.size
                AppLog.i("PTP", "storage=${storageId.toUIntString()} handles=${handles.size}")
                handles.sortedDescending().forEachIndexed { index, handle ->
                    throwIfStopped(shouldStop)
                    val info = session.getObjectInfo(handle)
                    val imageId = imageId(info)
                    if (!seenImageIds.add(imageId)) {
                        AppLog.w("PTP", "skip duplicate handle=${handle.toUIntString()} imageId=$imageId")
                        return@forEach
                    }
                    val reusedImage = reusableImages[imageId]?.takeIf { it.hasUsableCachedContent() }
                    onProgress(
                        UsbScanProgress(
                            scannedCount = scannedCount,
                            indexedCount = images.size,
                            totalCount = totalCount,
                            currentName = reusedImage?.displayName ?: info.fileName.ifBlank { "ptp_${handle.toUIntString()}.jpg" },
                            phase = UsbScanPhase.SCANNING
                        )
                    )
                    if (reusedImage != null) {
                        scannedCount += 1
                        images += reusedImage
                        onProgress(
                            UsbScanProgress(
                                scannedCount = scannedCount,
                                indexedCount = images.size,
                                totalCount = totalCount,
                                currentName = reusedImage.displayName,
                                indexedImage = reusedImage
                            )
                        )
                        AppLog.i("PTP", "reuse cached image handle=${handle.toUIntString()} name=${reusedImage.displayName}")
                        return@forEachIndexed
                    }

                    scannedCount += 1
                    AppLog.i(
                        "PTP",
                        "object ${index + 1}/${handles.size} handle=${handle.toUIntString()} " +
                            "format=0x${info.format.toString(16)} name=${info.fileName} size=${info.size}"
                    )
                    val displayName = info.fileName.ifBlank { "ptp_${handle.toUIntString()}.jpg" }
                    onProgress(
                        UsbScanProgress(
                            scannedCount = scannedCount,
                            indexedCount = images.size,
                            totalCount = totalCount,
                            currentName = displayName
                        )
                    )
                    if (!info.isSupportedImage()) return@forEachIndexed
                    throwIfStopped(shouldStop)
                    val thumbnailBytes = session.getThumb(handle)
                    if (thumbnailBytes.isEmpty()) {
                        AppLog.w("PTP", "skip handle=${handle.toUIntString()} empty thumbnail")
                        return@forEachIndexed
                    }
                    AppLog.i(
                        "PTP",
                        "thumbnail handle=${handle.toUIntString()} bytes=${thumbnailBytes.size} declared=${info.thumbSize}"
                    )
                    val thumbnailFile = uniqueFile(thumbnailName(info.fileName, handle))
                    thumbnailFile.outputStream().use { it.write(thumbnailBytes) }

                    throwIfStopped(shouldStop)
                    val exifInfo = session.readPartialExif(handle, info)
                    val takenTime = exifInfo?.takenTime ?: info.captureTime
                    val modified = info.modifiedTime ?: takenTime ?: System.currentTimeMillis()
                    val image = OtgImage(
                        id = imageId,
                        documentUri = Uri.EMPTY.toString(),
                        displayName = displayName,
                        relativePath = displayName,
                        mimeType = info.mimeType(),
                        sizeBytes = info.size,
                        lastModified = modified,
                        sortTime = modified,
                        thumbnailPath = thumbnailFile.absolutePath,
                        width = info.width.takeIf { it > 0 } ?: exifInfo?.width,
                        height = info.height.takeIf { it > 0 } ?: exifInfo?.height,
                        exifInfo = exifInfo.withObjectInfoFallback(info, modified)
                    )
                    images += image
                    onProgress(
                        UsbScanProgress(
                            scannedCount = scannedCount,
                            indexedCount = images.size,
                            totalCount = totalCount,
                            currentName = displayName,
                            indexedImage = image
                        )
                    )
                }
            }
            session.closeSession()

            if (images.isEmpty()) {
                throw IllegalStateException("PTP 设备中没有找到可读取的图片对象")
            }
            AppLog.i("PTP", "scanImages success count=${images.size}")
            return images.sortedByDescending { it.sortTime }
        } finally {
            runCatching { connection.releaseInterface(usbInterface) }
            connection.close()
            AppLog.i("PTP", "connection closed")
        }
    }

    private fun findPtpInterface(device: UsbDevice): UsbInterface? {
        return (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
    }

    private fun throwIfStopped(shouldStop: () -> Boolean) {
        if (shouldStop()) {
            throw kotlinx.coroutines.CancellationException("用户停止相机扫描")
        }
    }

    private fun imageId(info: PtpObjectInfo): String {
        return cameraImageId(
            displayName = info.fileName,
            sizeBytes = info.size,
            modifiedTime = info.modifiedTime ?: info.captureTime,
            width = info.width,
            height = info.height
        )
    }

    private fun buildReusableImageMap(images: List<OtgImage>): Map<String, OtgImage> {
        val result = mutableMapOf<String, OtgImage>()
        images.forEach { image ->
            result[image.id] = image
            result[cameraImageId(
                displayName = image.displayName,
                sizeBytes = image.sizeBytes,
                modifiedTime = image.lastModified.takeIf { it > 0L } ?: image.sortTime,
                width = image.width ?: image.exifInfo?.width ?: 0,
                height = image.height ?: image.exifInfo?.height ?: 0
            )] = image
        }
        return result
    }

    private fun OtgImage.hasUsableCachedContent(): Boolean {
        val thumbnail = thumbnailPath?.let { File(it) }
        return thumbnail?.exists() == true || documentUri.isNotBlank()
    }

    private fun findEndpoints(usbInterface: UsbInterface): PtpEndpoints {
        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        var interruptIn: UsbEndpoint? = null
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            val direction = endpoint.direction
            val type = endpoint.type
            AppLog.i(
                "PTP",
                "endpoint index=$index address=${endpoint.address} type=$type direction=$direction maxPacket=${endpoint.maxPacketSize}"
            )
            if (type == UsbConstants.USB_ENDPOINT_XFER_BULK && direction == UsbConstants.USB_DIR_IN) {
                bulkIn = endpoint
            } else if (type == UsbConstants.USB_ENDPOINT_XFER_BULK && direction == UsbConstants.USB_DIR_OUT) {
                bulkOut = endpoint
            } else if (type == UsbConstants.USB_ENDPOINT_XFER_INT && direction == UsbConstants.USB_DIR_IN) {
                interruptIn = endpoint
            }
        }
        return PtpEndpoints(
            bulkIn = bulkIn ?: throw IllegalStateException("PTP 接口没有 bulk IN endpoint"),
            bulkOut = bulkOut ?: throw IllegalStateException("PTP 接口没有 bulk OUT endpoint"),
            interruptIn = interruptIn
        )
    }

    private fun thumbnailName(fileName: String, handle: Int): String {
        val sourceName = fileName.ifBlank { "ptp_${handle.toUIntString()}.jpg" }
        val dotIndex = sourceName.lastIndexOf('.')
        val base = if (dotIndex > 0) sourceName.substring(0, dotIndex) else sourceName
        return "${base}_thumb.jpg"
    }

    private fun uniqueFile(name: String): File {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var file = File(cacheDirectory, safeName)
        if (!file.exists()) return file

        val dotIndex = safeName.lastIndexOf('.')
        val base = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
        val extension = if (dotIndex > 0) safeName.substring(dotIndex) else ""
        var index = 1
        while (file.exists()) {
            file = File(cacheDirectory, "${base}_$index$extension")
            index += 1
        }
        return file
    }

    private fun deleteDirectoryContents(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteDirectoryContents(file)
            }
            file.delete()
        }
    }
}

private data class PtpEndpoints(
    val bulkIn: UsbEndpoint,
    val bulkOut: UsbEndpoint,
    val interruptIn: UsbEndpoint?
)

private class PtpUsbSession(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) {
    private var nextTransactionId = 1
    private val readBuffer = ByteArrayOutputStream()

    fun getDeviceInfo() {
        val result = transact(PtpOperation.GET_DEVICE_INFO, transactionId = 0)
        AppLog.i("PTP", "GetDeviceInfo dataBytes=${result.data.size}")
        checkResponse("GetDeviceInfo", result.responseCode)
    }

    fun openSession(sessionId: Int) {
        val result = transact(PtpOperation.OPEN_SESSION, sessionId, transactionId = 0)
        checkResponse("OpenSession", result.responseCode)
    }

    fun closeSession() {
        val result = transact(PtpOperation.CLOSE_SESSION)
        AppLog.i("PTP", "CloseSession response=0x${result.responseCode.toString(16)}")
    }

    fun getStorageIds(): IntArray {
        val result = transact(PtpOperation.GET_STORAGE_IDS)
        checkResponse("GetStorageIDs", result.responseCode)
        return parseUInt32Array(result.data)
    }

    fun getObjectHandles(storageId: Int): IntArray {
        val result = transact(
            operationCode = PtpOperation.GET_OBJECT_HANDLES,
            storageId,
            0,
            PTP_PARENT_ALL
        )
        checkResponse("GetObjectHandles", result.responseCode)
        return parseUInt32Array(result.data)
    }

    fun getObjectInfo(handle: Int): PtpObjectInfo {
        val result = transact(PtpOperation.GET_OBJECT_INFO, handle)
        checkResponse("GetObjectInfo", result.responseCode)
        return parseObjectInfo(result.data)
    }

    fun getThumb(handle: Int): ByteArray {
        val result = transact(PtpOperation.GET_THUMB, handle)
        checkResponse("GetThumb", result.responseCode)
        return result.data
    }

    fun getPartialObject(handle: Int, offset: Long, maxBytes: Int): ByteArray {
        val result = transact(
            operationCode = PtpOperation.GET_PARTIAL_OBJECT,
            handle,
            (offset and 0xffffffffL).toInt(),
            maxBytes
        )
        checkResponse("GetPartialObject", result.responseCode)
        return result.data
    }

    fun readPartialExif(handle: Int, info: PtpObjectInfo): ImageExifInfo? {
        if (!info.canReadExifHeader()) return null
        val maxBytes = minOf(PTP_EXIF_HEADER_BYTES.toLong(), info.size).toInt()
        return runCatching {
            val headerBytes = getPartialObject(handle, 0L, maxBytes)
            if (headerBytes.isEmpty()) return null
            ByteArrayInputStream(headerBytes).use { input ->
                ExifMetadataReader.read(input)
            }
        }.onFailure { error ->
            AppLog.w("PTP", "readPartialExif failed handle=${handle.toUIntString()} ${error.message ?: "unknown"}")
        }.getOrNull()
    }

    private fun transact(
        operationCode: Int,
        vararg params: Int,
        transactionId: Int? = null
    ): PtpTransactionResult {
        val currentTransactionId = transactionId ?: nextTransactionId++
        AppLog.i(
            "PTP",
            "cmd op=0x${operationCode.toString(16)} tx=$currentTransactionId params=${params.joinToString { it.toUIntString() }}"
        )
        writeCommand(operationCode, currentTransactionId, params)

        val first = readContainer("op=0x${operationCode.toString(16)} first")
        val data: ByteArray
        val response: PtpContainer
        if (first.type == PtpContainer.TYPE_DATA) {
            data = first.payload
            response = readContainer("op=0x${operationCode.toString(16)} response")
        } else {
            data = ByteArray(0)
            response = first
        }

        AppLog.i(
            "PTP",
            "rsp op=0x${operationCode.toString(16)} tx=$currentTransactionId " +
                "type=${response.type} code=0x${response.code.toString(16)} data=${data.size}"
        )
        return PtpTransactionResult(response.code, data)
    }

    private fun writeCommand(operationCode: Int, transactionId: Int, params: IntArray) {
        val length = PTP_HEADER_SIZE + params.size * 4
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length)
        buffer.putShort(PtpContainer.TYPE_COMMAND.toShort())
        buffer.putShort(operationCode.toShort())
        buffer.putInt(transactionId)
        params.forEach { buffer.putInt(it) }
        val bytes = buffer.array()
        var written = -1
        for (attempt in 1..PTP_WRITE_RETRY_COUNT) {
            written = connection.bulkTransfer(bulkOut, bytes, bytes.size, USB_TIMEOUT_MS)
            AppLog.i(
                "PTP",
                "writeCommand attempt=$attempt endpoint=${bulkOut.address} bytes=${bytes.size} written=$written"
            )
            if (written == bytes.size) break
            Thread.sleep(PTP_WRITE_RETRY_DELAY_MS)
        }
        if (written != bytes.size) {
            throw IllegalStateException("PTP 命令写入失败：expected=${bytes.size} actual=$written")
        }
    }

    private fun readContainer(label: String): PtpContainer {
        val header = readExact(PTP_HEADER_SIZE, "$label header")
        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = headerBuffer.int
        val type = headerBuffer.short.toInt() and 0xffff
        val code = headerBuffer.short.toInt() and 0xffff
        val transaction = headerBuffer.int
        if (length < PTP_HEADER_SIZE) {
            throw IllegalStateException("PTP 容器长度异常：$length")
        }
        val payloadLength = length - PTP_HEADER_SIZE
        val payload = if (payloadLength > 0) readExact(payloadLength, "$label payload") else ByteArray(0)
        AppLog.i(
            "PTP",
            "readContainer $label length=$length type=$type code=0x${code.toString(16)} tx=$transaction payload=$payloadLength"
        )
        return PtpContainer(length, type, code, transaction, payload)
    }

    private fun readExact(length: Int, label: String): ByteArray {
        val output = ByteArrayOutputStream(length)
        while (output.size() < length) {
            val buffered = readFromBuffer(length - output.size())
            if (buffered.isNotEmpty()) {
                output.write(buffered)
                AppLog.i("PTP", "readExact $label buffered=${buffered.size} total=${output.size()}/$length")
                continue
            }

            val remaining = length - output.size()
            val requestSize = if (remaining <= PTP_HEADER_SIZE) {
                PTP_READ_CHUNK_SIZE
            } else {
                minOf(PTP_READ_CHUNK_SIZE, remaining)
            }
            val buffer = ByteArray(requestSize)
            val read = connection.bulkTransfer(bulkIn, buffer, requestSize, USB_TIMEOUT_MS)
            AppLog.i("PTP", "readExact $label request=$requestSize read=$read total=${output.size() + read}/$length")
            if (read <= 0) {
                throw IllegalStateException("PTP 读取超时或失败：$label read=$read")
            }
            val needed = length - output.size()
            val consumed = minOf(needed, read)
            output.write(buffer, 0, consumed)
            if (read > consumed) {
                readBuffer.write(buffer, consumed, read - consumed)
                AppLog.i("PTP", "readExact $label storedExtra=${read - consumed}")
            }
        }
        return output.toByteArray()
    }

    private fun readFromBuffer(maxBytes: Int): ByteArray {
        val bytes = readBuffer.toByteArray()
        if (bytes.isEmpty()) return ByteArray(0)
        val count = minOf(maxBytes, bytes.size)
        readBuffer.reset()
        if (bytes.size > count) {
            readBuffer.write(bytes, count, bytes.size - count)
        }
        return bytes.copyOfRange(0, count)
    }

    private fun checkResponse(name: String, responseCode: Int) {
        if (responseCode != PtpResponse.OK) {
            throw IllegalStateException("$name 失败：response=0x${responseCode.toString(16)}")
        }
    }

    private fun parseUInt32Array(bytes: ByteArray): IntArray {
        if (bytes.size < 4) return intArrayOf()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int
        val values = IntArray(count.coerceAtMost((bytes.size - 4) / 4))
        for (index in values.indices) {
            values[index] = buffer.int
        }
        AppLog.i("PTP", "parseUInt32Array count=$count values=${values.joinToString { it.toUIntString() }}")
        return values
    }

    private fun parseObjectInfo(bytes: ByteArray): PtpObjectInfo {
        val reader = PtpDataReader(bytes)
        reader.u32() // storage id
        val format = reader.u16()
        reader.u16() // protection status
        val size = reader.u32().toLong() and 0xffffffffL
        reader.u16() // thumb format
        val thumbSize = reader.u32().toLong() and 0xffffffffL
        reader.u32() // thumb width
        reader.u32() // thumb height
        val width = reader.u32()
        val height = reader.u32()
        reader.u32() // bit depth
        reader.u32() // parent
        reader.u16() // association type
        reader.u32() // association desc
        reader.u32() // sequence number
        val fileName = reader.string()
        val captureTime = parsePtpDate(reader.string())
        val modifiedTime = parsePtpDate(reader.string())
        reader.string() // keywords
        return PtpObjectInfo(
            format = format,
            size = size,
            thumbSize = thumbSize,
            width = width,
            height = height,
            fileName = fileName,
            captureTime = captureTime,
            modifiedTime = modifiedTime
        )
    }

    private fun parsePtpDate(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.length < 15) return null
        val datePart = trimmed.take(15)
        val zonePart = trimmed.drop(15).takeIf { it.length >= 5 && (it.first() == '+' || it.first() == '-') }
        return runCatching {
            SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                if (zonePart != null) {
                    timeZone = TimeZone.getTimeZone("GMT$zonePart")
                }
            }.parse(datePart)?.time
        }.getOrNull()
    }
}

private fun ImageExifInfo?.withObjectInfoFallback(info: PtpObjectInfo, fallbackTime: Long): ImageExifInfo {
    val objectWidth = info.width.takeIf { it > 0 }
    val objectHeight = info.height.takeIf { it > 0 }
    if (this == null) {
        return ImageExifInfo(
            takenTime = info.captureTime ?: fallbackTime,
            width = objectWidth,
            height = objectHeight
        )
    }
    return copy(
        takenTime = takenTime ?: info.captureTime ?: fallbackTime,
        width = width ?: objectWidth,
        height = height ?: objectHeight
    )
}

private data class PtpTransactionResult(
    val responseCode: Int,
    val data: ByteArray
)

private data class PtpContainer(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val payload: ByteArray
) {
    companion object {
        const val TYPE_COMMAND = 1
        const val TYPE_DATA = 2
    }
}

private data class PtpObjectInfo(
    val format: Int,
    val size: Long,
    val thumbSize: Long,
    val width: Int,
    val height: Int,
    val fileName: String,
    val captureTime: Long?,
    val modifiedTime: Long?
) {
    fun isSupportedImage(): Boolean {
        val lowerName = fileName.lowercase(Locale.ROOT)
        return format == PtpFormat.EXIF_JPEG ||
            format == PtpFormat.PNG ||
            lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png")
    }

    fun mimeType(): String {
        val lowerName = fileName.lowercase(Locale.ROOT)
        return when {
            format == PtpFormat.PNG || lowerName.endsWith(".png") -> "image/png"
            else -> "image/jpeg"
        }
    }

    fun canReadExifHeader(): Boolean {
        val lowerName = fileName.lowercase(Locale.ROOT)
        return size > 0L && (format == PtpFormat.EXIF_JPEG || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg"))
    }
}

private class PtpDataReader(
    private val bytes: ByteArray
) {
    private var offset = 0

    fun u16(): Int {
        if (offset + 2 > bytes.size) return 0
        val value = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
        offset += 2
        return value
    }

    fun u32(): Int {
        if (offset + 4 > bytes.size) return 0
        val value = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
        offset += 4
        return value
    }

    fun string(): String {
        if (offset >= bytes.size) return ""
        val charCount = bytes[offset].toInt() and 0xff
        offset += 1
        if (charCount == 0) return ""
        val byteCount = (charCount - 1) * 2
        if (offset + byteCount > bytes.size) {
            offset = bytes.size
            return ""
        }
        val value = String(bytes, offset, byteCount, Charsets.UTF_16LE)
        offset += byteCount + 2
        return value
    }
}

private object PtpOperation {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val GET_PARTIAL_OBJECT = 0x101B
}

private object PtpResponse {
    const val OK = 0x2001
}

private object PtpFormat {
    const val EXIF_JPEG = 0x3801
    const val PNG = 0x380B
}

private const val PTP_HEADER_SIZE = 12
private const val PTP_PARENT_ALL = -1
private const val PTP_READ_CHUNK_SIZE = 16 * 1024
private const val PTP_EXIF_HEADER_BYTES = 256 * 1024
private const val USB_TIMEOUT_MS = 10_000
private const val PTP_WRITE_RETRY_COUNT = 3
private const val PTP_WRITE_RETRY_DELAY_MS = 120L

private fun Int.toUIntString(): String {
    return (toLong() and 0xffffffffL).toString()
}
