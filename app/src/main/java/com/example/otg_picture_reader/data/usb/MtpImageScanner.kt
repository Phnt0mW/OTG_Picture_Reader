package com.example.otg_picture_reader.data.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.otg_picture_reader.data.local.ExifMetadataReader
import com.example.otg_picture_reader.data.model.ImageExifInfo
import com.example.otg_picture_reader.data.model.OtgImage
import com.example.otg_picture_reader.util.AppLog
import java.io.File
import java.util.Locale

class MtpImageScanner(
    context: Context
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val cacheDirectory = File(appContext.cacheDir, "mtp_images").apply { mkdirs() }

    fun findCandidateDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values.toList()
        devices.forEach { device ->
            AppLog.i("MTP", describeUsbDevice(device))
        }
        return devices.firstOrNull { device -> device.hasStillImageInterface() }
            ?: devices.firstOrNull { device -> device.hasVendorSpecificInterface() }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun scanImages(
        existingImages: List<OtgImage> = emptyList(),
        onProgress: (UsbScanProgress) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): List<OtgImage> {
        throwIfStopped(shouldStop)
        val device = findCandidateDevice()
            ?: throw IllegalStateException("没有发现可读取的 PTP/MTP 设备")
        AppLog.i("MTP", "selected ${describeUsbDevice(device)}")
        if (!usbManager.hasPermission(device)) {
            throw SecurityException("还没有 USB 设备访问权限")
        }

        val reusableImages = buildReusableImageMap(existingImages)

        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("无法打开 USB 设备连接")
        val mtpDevice = MtpDevice(device)

        try {
            if (!mtpDevice.open(connection)) {
                throw IllegalStateException("无法打开 PTP/MTP 设备")
            }

            val images = mutableListOf<OtgImage>()
            val seenImageIds = mutableSetOf<String>()
            var scannedCount = 0
            var metadataCount = 0
            val deviceInfo = mtpDevice.deviceInfo
            AppLog.i(
                "MTP",
                "deviceInfo manufacturer=${deviceInfo?.manufacturer} model=${deviceInfo?.model} " +
                    "version=${deviceInfo?.version} serial=${deviceInfo?.serialNumber}"
            )
            val storageIds = mtpDevice.storageIds?.takeIf { it.isNotEmpty() }
                ?: intArrayOf(MTP_STORAGE_ALL)
            AppLog.i("MTP", "storageIds=${storageIds.joinToString()}")

            storageIds.forEach { storageId ->
                throwIfStopped(shouldStop)
                collectImages(
                    mtpDevice = mtpDevice,
                    deviceId = device.deviceId,
                    storageId = storageId,
                    parentHandle = 0,
                    parentPath = "",
                    images = images,
                    existingImages = reusableImages,
                    seenImageIds = seenImageIds,
                    shouldStop = shouldStop,
                    onObjectScanned = { name ->
                        scannedCount += 1
                        onProgress(
                            UsbScanProgress(
                                scannedCount = scannedCount,
                                indexedCount = images.size,
                                currentName = name
                            )
                        )
                    },
                    onImageIndexed = { name, image ->
                        onProgress(
                            UsbScanProgress(
                                scannedCount = scannedCount,
                                indexedCount = images.size,
                                currentName = name,
                                indexedImage = image
                            )
                        )
                    },
                    onMetadataRead = { name ->
                        metadataCount += 1
                        onProgress(
                            UsbScanProgress(
                                scannedCount = metadataCount,
                                indexedCount = images.size,
                                currentName = name,
                                phase = UsbScanPhase.READING_TIME
                            )
                        )
                    }
                )
            }

            if (images.isEmpty()) {
                throw IllegalStateException(
                    "设备没有暴露可读取的照片对象。请确认设备端 USB 模式为照片传输/PTP 或文件传输/MTP。"
                )
            }

            return images.sortedByDescending { it.sortTime }
        } finally {
            runCatching { mtpDevice.close() }
            connection.close()
        }
    }

    private fun collectImages(
        mtpDevice: MtpDevice,
        deviceId: Int,
        storageId: Int,
        parentHandle: Int,
        parentPath: String,
        images: MutableList<OtgImage>,
        existingImages: Map<String, OtgImage>,
        seenImageIds: MutableSet<String>,
        shouldStop: () -> Boolean,
        onObjectScanned: (String) -> Unit,
        onImageIndexed: (String, OtgImage) -> Unit,
        onMetadataRead: (String) -> Unit
    ) {
        throwIfStopped(shouldStop)
        val handles = mtpDevice.getObjectHandles(storageId, 0, parentHandle)
            ?: mtpDevice.getObjectHandles(storageId, 0, MTP_PARENT_ALL)
            ?: return
        AppLog.i("MTP", "collect storageId=$storageId parent=$parentHandle handles=${handles.size}")
        handles.sortedDescending().forEach { handle ->
            throwIfStopped(shouldStop)
            val info = mtpDevice.getObjectInfo(handle)
            if (info == null) {
                AppLog.w("MTP", "objectInfo null handle=$handle")
                return@forEach
            }
            val name = info.name?.takeIf { it.isNotBlank() } ?: "mtp_$handle.jpg"
            onMetadataRead(name)
            val relativePath = if (parentPath.isBlank()) name else "$parentPath/$name"
            onObjectScanned(name)
            AppLog.i(
                "MTP",
                "object handle=$handle format=0x${info.format.toString(16)} name=$name size=${objectSize(info.compressedSize)}"
            )

            if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                collectImages(
                    mtpDevice = mtpDevice,
                    deviceId = deviceId,
                    storageId = storageId,
                    parentHandle = handle,
                    parentPath = relativePath,
                    images = images,
                    existingImages = existingImages,
                    seenImageIds = seenImageIds,
                    shouldStop = shouldStop,
                    onObjectScanned = onObjectScanned,
                    onImageIndexed = onImageIndexed,
                    onMetadataRead = onMetadataRead
                )
                return@forEach
            }

            if (!isImageObject(name, info.format)) return@forEach

            val imageId = imageId(name, info)
            if (!seenImageIds.add(imageId)) {
                AppLog.w("MTP", "skip duplicate handle=$handle imageId=$imageId")
                return@forEach
            }
            val reusedImage = existingImages[imageId]?.takeIf { it.hasUsableCachedContent() }
            if (reusedImage != null) {
                images += reusedImage
                AppLog.i("MTP", "reuse cached image handle=$handle name=${reusedImage.displayName}")
                onImageIndexed(name, reusedImage)
                return@forEach
            }

            val size = objectSize(info.compressedSize)
            if (size <= 0L || size > Int.MAX_VALUE) {
                AppLog.w("MTP", "skip object handle=$handle name=$name size=$size")
                return@forEach
            }

            throwIfStopped(shouldStop)
            val bytes = mtpDevice.getObject(handle, size.toInt()) ?: return@forEach
            throwIfStopped(shouldStop)
            val localFile = uniqueFile(name)
            localFile.outputStream().use { output ->
                output.write(bytes)
            }

            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                localFile
            )
            val exifInfo = localFile.inputStream().use { input ->
                runCatching { ExifMetadataReader.read(input) }.getOrNull()
            }
            val modified = exifInfo?.takenTime ?: mtpTimeMillis(info.dateModified)
            val image = OtgImage(
                id = imageId,
                documentUri = uri.toString(),
                displayName = name,
                relativePath = relativePath,
                mimeType = guessMimeType(name, info.format),
                sizeBytes = localFile.length(),
                lastModified = modified,
                sortTime = modified,
                width = exifInfo?.width,
                height = exifInfo?.height,
                exifInfo = exifInfo ?: ImageExifInfo(takenTime = modified)
            )
            images += image
            onImageIndexed(name, image)
        }
    }

    private fun throwIfStopped(shouldStop: () -> Boolean) {
        if (shouldStop()) {
            throw kotlinx.coroutines.CancellationException("用户停止相机扫描")
        }
    }

    private fun imageId(name: String, info: android.mtp.MtpObjectInfo): String {
        return cameraImageId(
            displayName = name,
            sizeBytes = objectSize(info.compressedSize),
            modifiedTime = mtpTimeMillis(info.dateModified),
            width = 0,
            height = 0
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

    private fun isImageObject(name: String, format: Int): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        return lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png") ||
            lowerName.endsWith(".webp") ||
            lowerName.endsWith(".heic") ||
            format == MtpConstants.FORMAT_EXIF_JPEG ||
            format == MtpConstants.FORMAT_PNG
    }

    private fun guessMimeType(name: String, format: Int): String {
        val lowerName = name.lowercase(Locale.ROOT)
        return when {
            lowerName.endsWith(".png") || format == MtpConstants.FORMAT_PNG -> "image/png"
            lowerName.endsWith(".webp") -> "image/webp"
            lowerName.endsWith(".heic") -> "image/heic"
            else -> "image/jpeg"
        }
    }

    private fun objectSize(compressedSize: Int): Long {
        return compressedSize.toLong() and 0xffffffffL
    }

    private fun mtpTimeMillis(seconds: Long): Long {
        return if (seconds > 0L) seconds * 1000L else System.currentTimeMillis()
    }

    private fun deleteDirectoryContents(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteDirectoryContents(file)
            }
            file.delete()
        }
    }

    private fun UsbDevice.hasStillImageInterface(): Boolean {
        return (0 until interfaceCount).any { index ->
            getInterface(index).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
        }
    }

    private fun UsbDevice.hasVendorSpecificInterface(): Boolean {
        return (0 until interfaceCount).any { index ->
            getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
        }
    }

    private fun describeUsbDevice(device: UsbDevice): String {
        val interfaces = (0 until device.interfaceCount).joinToString { index ->
            val usbInterface = device.getInterface(index)
            "#$index class=${usbInterface.interfaceClass} subclass=${usbInterface.interfaceSubclass} " +
                "protocol=${usbInterface.interfaceProtocol} endpoints=${usbInterface.endpointCount}"
        }
        return "usbDevice name=${device.deviceName} vendorId=${device.vendorId} productId=${device.productId} " +
            "manufacturer=${device.manufacturerName} product=${device.productName} " +
            "class=${device.deviceClass} subclass=${device.deviceSubclass} protocol=${device.deviceProtocol} " +
            "interfaces=[$interfaces]"
    }

    private companion object {
        private const val MTP_STORAGE_ALL = -1
        private const val MTP_PARENT_ALL = 0
    }
}
