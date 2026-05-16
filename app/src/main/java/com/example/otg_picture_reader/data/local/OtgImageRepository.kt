package com.example.otg_picture_reader.data.local

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.example.otg_picture_reader.data.model.ImageExifInfo
import com.example.otg_picture_reader.data.model.OtgImage
import com.example.otg_picture_reader.data.model.ScanState
import com.example.otg_picture_reader.data.usb.MtpImageScanner
import com.example.otg_picture_reader.data.usb.PtpImageScanner
import com.example.otg_picture_reader.data.usb.UsbCameraSessionLock
import com.example.otg_picture_reader.data.usb.UsbScanPhase
import com.example.otg_picture_reader.data.usb.UsbScanProgress
import com.example.otg_picture_reader.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class OtgImageRepository(
    context: Context,
    private val cacheStore: OtgCacheStore,
    private val mtpImageScanner: MtpImageScanner,
    private val ptpImageScanner: PtpImageScanner
) {
    private val appContext = context.applicationContext
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val thumbnailDirectory = File(appContext.cacheDir, "thumbnails").apply { mkdirs() }
    private val sharedDirectory = File(appContext.cacheDir, "shared").apply { mkdirs() }
    private var cameraScanJob: Job? = null
    private val stopCameraScanRequested = AtomicBoolean(false)

    private val _rootUri = MutableStateFlow(cacheStore.readRootUri())
    val rootUri: StateFlow<String?> = _rootUri.asStateFlow()

    private val _images = MutableStateFlow(cacheStore.readImages())
    val images: StateFlow<List<OtgImage>> = _images.asStateFlow()

    private val _scanState = MutableStateFlow(ScanState(indexedCount = _images.value.size))
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    fun setRootUri(uri: Uri) {
        val value = uri.toString()
        cacheStore.saveRootUri(value)
        _rootUri.value = value
        _scanState.update {
            it.copy(statusMessage = "OTG 目录已连接，可以开始扫描。")
        }
    }

    fun clearRootUri() {
        cacheStore.saveRootUri(null)
        _rootUri.value = null
        _images.value = emptyList()
        cacheStore.clearImages()
        _scanState.value = ScanState(statusMessage = "已清除当前 OTG 目录。")
    }

    fun scanImages() {
        val uriString = _rootUri.value ?: return
        repositoryScope.launch {
            val root = DocumentFile.fromTreeUri(appContext, Uri.parse(uriString))
            if (root == null || !root.exists() || !root.isDirectory) {
                _scanState.value = ScanState(statusMessage = "无法访问 OTG 目录，请重新选择。")
                return@launch
            }

            _scanState.value = ScanState(
                isScanning = true,
                scannedCount = 0,
                indexedCount = 0,
                statusMessage = "正在扫描 OTG 图片，请稍候。"
            )

            deleteDirectoryContents(thumbnailDirectory)

            val indexedImages = mutableListOf<OtgImage>()
            val pendingDirectories = ArrayDeque<Pair<DocumentFile, String>>()
            pendingDirectories.add(root to "")

            while (pendingDirectories.isNotEmpty()) {
                val (current, prefix) = pendingDirectories.removeFirst()
                val children = current.listFiles().sortedBy { child -> child.name?.lowercase(Locale.ROOT).orEmpty() }
                for (child in children) {
                    if (child.isDirectory) {
                        val nextPrefix = buildRelativePath(prefix, child.name.orEmpty())
                        pendingDirectories.add(child to nextPrefix)
                        continue
                    }

                    _scanState.update {
                        it.copy(scannedCount = it.scannedCount + 1)
                    }

                    if (!child.isFile || !child.isImageFile()) {
                        continue
                    }

                    val relativePath = buildRelativePath(prefix, child.name.orEmpty())
                    val exifInfo = readExifInfo(child.uri)
                    val sortTime = exifInfo?.takenTime ?: child.lastModified()
                    indexedImages += OtgImage(
                        id = UUID.randomUUID().toString(),
                        documentUri = child.uri.toString(),
                        displayName = child.name ?: "未命名图片",
                        relativePath = relativePath,
                        mimeType = child.type ?: "image/*",
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                        sortTime = sortTime,
                        width = exifInfo?.width,
                        height = exifInfo?.height,
                        exifInfo = exifInfo
                    )

                    _scanState.update {
                        it.copy(indexedCount = indexedImages.size)
                    }
                }
            }

            val sortedImages = indexedImages.sortedByDescending { it.sortTime }
            _images.value = sortedImages
            cacheStore.saveImages(sortedImages)

            generateThumbnails(sortedImages)

            _scanState.value = ScanState(
                isScanning = false,
                scannedCount = _scanState.value.scannedCount,
                indexedCount = _images.value.size,
                statusMessage = if (_images.value.isEmpty()) {
                    "没有找到图片文件。"
                } else {
                    "扫描完成，共找到 ${_images.value.size} 张图片。"
                }
            )
        }
    }

    fun hasMtpDeviceAccess(): Boolean {
        val device = mtpImageScanner.findCandidateDevice() ?: return false
        return mtpImageScanner.hasPermission(device)
    }

    fun scanMtpImages() {
        val activeJob = cameraScanJob
        if (activeJob?.isActive == true) {
            AppLog.w("Repository", "scanMtpImages ignored: camera scan already running")
            _scanState.update {
                it.copy(statusMessage = "相机正在扫描中，请稍候。")
            }
            return
        }

        stopCameraScanRequested.set(false)
        cameraScanJob = repositoryScope.launch {
            _scanState.value = ScanState(
                isScanning = true,
                scannedCount = 0,
                indexedCount = 0,
                statusMessage = "正在读取相机/PTP/MTP 设备图片。"
            )

            val originalImageKeys = _images.value.mapTo(mutableSetOf()) { it.cameraDedupeKey() }
            runCatching {
                val device = mtpImageScanner.findCandidateDevice()
                    ?: throw IllegalStateException("没有发现可读取的 PTP/MTP 设备")
                val progressHandler = ::updateUsbScanProgress
                val existingImages = _images.value.filter { image -> image.isCameraImage() }
                UsbCameraSessionLock.mutex.lock()
                try {
                    runCatching {
                        AppLog.i("Repository", "scanMtpImages try custom PTP")
                        withTimeout(PTP_SCAN_TIMEOUT_MS) {
                            ptpImageScanner.scanImages(
                                device = device,
                                existingImages = existingImages,
                                onProgress = progressHandler,
                                shouldStop = ::isCameraScanStopRequested
                            )
                        }
                    }.recoverCatching { ptpError ->
                        if (ptpError is CancellationException || isCameraScanStopRequested()) {
                            throw CancellationException("用户停止相机扫描")
                        }
                        AppLog.e("Repository", "custom PTP failed, fallback to MtpDevice", ptpError)
                        _scanState.value = ScanState(
                            isScanning = true,
                            scannedCount = 0,
                            indexedCount = 0,
                            statusMessage = "PTP 读取失败，正在尝试 MTP 模式。"
                        )
                        runCatching {
                            withTimeout(PTP_SCAN_TIMEOUT_MS) {
                                mtpImageScanner.scanImages(
                                    existingImages = existingImages,
                                    onProgress = progressHandler,
                                    shouldStop = ::isCameraScanStopRequested
                                )
                            }
                        }.getOrElse { mtpError ->
                            if (mtpError is CancellationException || isCameraScanStopRequested()) {
                                throw CancellationException("用户停止相机扫描")
                            }
                            AppLog.e("Repository", "MtpDevice fallback failed", mtpError)
                            throw IllegalStateException(
                                "PTP 读取失败：${ptpError.message ?: "未知错误"}；MTP fallback 也失败：${mtpError.message ?: "未知错误"}",
                                ptpError
                            )
                        }
                    }.getOrThrow()
                } finally {
                    UsbCameraSessionLock.mutex.unlock()
                }
            }.onSuccess { indexedImages ->
                if (isCameraScanStopRequested()) {
                    AppLog.i("Repository", "scanMtpImages stopped before final commit")
                    persistCurrentScanCache("已停止扫描，已保存当前 ${_images.value.size} 张缓存图片。")
                    return@onSuccess
                }
                AppLog.i("Repository", "scanMtpImages indexed count=${indexedImages.size}")
                val addedCount = indexedImages.count { it.cameraDedupeKey() !in originalImageKeys }
                _rootUri.value = null
                cacheStore.saveRootUri(null)
                val dedupedImages = dedupeCameraImages(indexedImages)
                _images.value = dedupedImages
                cacheStore.saveImages(dedupedImages)
                _scanState.value = ScanState(
                    isScanning = true,
                    scannedCount = dedupedImages.size,
                    indexedCount = dedupedImages.size,
                    statusMessage = if (addedCount > 0) {
                        "发现 $addedCount 张新增图片，正在整理缩略图。"
                    } else {
                        "没有发现新增图片，正在复用已缓存缩略图。"
                    }
                )
                generateThumbnails(dedupedImages)
                _scanState.value = ScanState(
                    isScanning = false,
                    scannedCount = dedupedImages.size,
                    indexedCount = _images.value.size,
                    statusMessage = if (_images.value.isEmpty()) {
                        "PTP/MTP 设备中没有找到图片。"
                    } else if (addedCount > 0) {
                        "增量扫描完成，新增 $addedCount 张，共 ${_images.value.size} 张图片。"
                    } else {
                        "增量扫描完成，没有新增图片，共 ${_images.value.size} 张。"
                    }
                )
            }.onFailure { error ->
                if (error is CancellationException) {
                    AppLog.i("Repository", "scanMtpImages cancelled")
                    persistCurrentScanCache("已停止扫描，已保存当前 ${_images.value.size} 张缓存图片。")
                    return@onFailure
                }
                AppLog.e("Repository", "scanMtpImages failed", error)
                _scanState.value = ScanState(
                    isScanning = false,
                    statusMessage = "读取 PTP/MTP 设备失败：${error.message ?: "请重试"}"
                )
            }.also {
                stopCameraScanRequested.set(false)
                cameraScanJob = null
            }
        }
    }

    fun stopCameraScan() {
        val activeJob = cameraScanJob
        if (activeJob?.isActive != true) {
            AppLog.w("Repository", "stopCameraScan ignored: no active camera scan")
            return
        }
        AppLog.i("Repository", "stopCameraScan")
        stopCameraScanRequested.set(true)
        activeJob.cancel(CancellationException("用户停止相机扫描"))
        persistCurrentScanCache("已停止扫描，已保存当前 ${_images.value.size} 张缓存图片。")
    }

    private fun isCameraScanStopRequested(): Boolean {
        return stopCameraScanRequested.get() || cameraScanJob?.isCancelled == true
    }

    private fun persistCurrentScanCache(message: String) {
        val cachedImages = dedupeCameraImages(_images.value).sortedByDescending { it.sortTime }
        _images.value = cachedImages
        cacheStore.saveImages(cachedImages)
        _scanState.value = ScanState(
            isScanning = false,
            scannedCount = _scanState.value.scannedCount,
            indexedCount = cachedImages.size,
            statusMessage = message
        )
    }

    private fun updateUsbScanProgress(progress: UsbScanProgress) {
        if (isCameraScanStopRequested()) return
        progress.indexedImage?.let { image ->
            mergeScannedImage(image)
        }
        val totalText = progress.totalCount?.takeIf { it > 0 }?.let { "/$it" }.orEmpty()
        val currentText = progress.currentName?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        val message = when (progress.phase) {
            UsbScanPhase.READING_TIME -> {
                "正在获取照片时间 ${progress.scannedCount}$totalText$currentText"
            }
            UsbScanPhase.SCANNING -> {
                "正在遍历相机图片 ${progress.scannedCount}$totalText，已索引 ${progress.indexedCount} 张$currentText"
            }
        }
        _scanState.value = ScanState(
            isScanning = true,
            scannedCount = progress.scannedCount,
            indexedCount = progress.indexedCount,
            statusMessage = message
        )
    }

    private fun mergeScannedImage(image: OtgImage) {
        _images.update { currentImages ->
            val existingIndex = currentImages.indexOfFirst { it.id == image.id }
            val merged = if (existingIndex >= 0) {
                currentImages.toMutableList().also { it[existingIndex] = image }
            } else {
                currentImages + image
            }
            dedupeCameraImages(merged).sortedByDescending { it.sortTime }
        }
    }

    private fun dedupeCameraImages(images: List<OtgImage>): List<OtgImage> {
        val result = LinkedHashMap<String, OtgImage>()
        images.sortedByDescending { it.sortTime }.forEach { image ->
            val key = image.cameraDedupeKey()
            val existing = result[key]
            if (existing == null || image.isPreferredOver(existing)) {
                result[key] = image
            }
        }
        return result.values.sortedByDescending { it.sortTime }
    }

    private fun OtgImage.isCameraImage(): Boolean {
        return id.startsWith("camera_") || id.startsWith("ptp_") || id.startsWith("mtp_")
    }

    private fun OtgImage.cameraDedupeKey(): String {
        val normalizedName = displayName.trim().lowercase(Locale.ROOT)
        val widthValue = width ?: exifInfo?.width ?: 0
        val heightValue = height ?: exifInfo?.height ?: 0
        val timeValue = lastModified.takeIf { it > 0L } ?: sortTime.takeIf { it > 0L } ?: 0L
        return listOf(
            normalizedName,
            sizeBytes.coerceAtLeast(0L).toString(),
            timeValue.toString()
        ).joinToString("|")
    }

    private fun OtgImage.isPreferredOver(other: OtgImage): Boolean {
        val hasThumbnail = !thumbnailPath.isNullOrBlank() && File(thumbnailPath).exists()
        val otherHasThumbnail = !other.thumbnailPath.isNullOrBlank() && File(other.thumbnailPath).exists()
        if (hasThumbnail != otherHasThumbnail) return hasThumbnail
        if (id.startsWith("camera_") != other.id.startsWith("camera_")) return id.startsWith("camera_")
        return sortTime >= other.sortTime
    }

    suspend fun saveToGallery(image: OtgImage): Result<String> {
        return runCatching {
            copyImageToGallery(image)
            "已保存到系统相册"
        }
    }

    suspend fun prepareShareUri(image: OtgImage): Result<Uri> {
        return runCatching {
            prepareShareUriInternal(image)
        }
    }

    suspend fun saveManyToGallery(images: List<OtgImage>): Result<String> {
        return runCatching {
            var successCount = 0
            images.forEach { image ->
                copyImageToGallery(image)
                successCount += 1
            }
            "已保存 $successCount 张图片到系统相册"
        }
    }

    suspend fun prepareShareUris(images: List<OtgImage>): Result<List<Uri>> {
        return runCatching {
            images.map { image -> prepareShareUriInternal(image) }
        }
    }

    private suspend fun generateThumbnails(images: List<OtgImage>) {
        if (images.isEmpty()) return

        AppLog.i("Repository", "generateThumbnails start count=${images.size}")
        val updated = images.toMutableList()
        var changed = false

        images.forEachIndexed { index, image ->
            AppLog.i("Repository", "generateThumbnail index=${index + 1}/${images.size} id=${image.id} name=${image.displayName}")
            val thumbnailFile = File(thumbnailDirectory, "${image.id}.jpg")
            if (!image.thumbnailPath.isNullOrBlank() && File(image.thumbnailPath).exists()) {
                updated[index] = image
                changed = true
            } else if (thumbnailFile.exists()) {
                updated[index] = image.copy(thumbnailPath = thumbnailFile.absolutePath)
                changed = true
            } else {
                val bitmap = decodeSampledBitmap(
                    resolver = appContext.contentResolver,
                    uri = Uri.parse(image.documentUri),
                    targetSize = THUMBNAIL_SIZE_PX
                )

                if (bitmap != null) {
                    FileOutputStream(thumbnailFile).use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                    }
                    bitmap.recycle()
                    updated[index] = image.copy(thumbnailPath = thumbnailFile.absolutePath)
                    changed = true
                }
            }

            if (changed && (index % 20 == 0 || index == images.lastIndex)) {
                _images.value = updated.toList()
                cacheStore.saveImages(_images.value)
                changed = false
            }
            if (index % 5 == 0 || index == images.lastIndex) {
                _scanState.update {
                    it.copy(
                        scannedCount = images.size,
                        indexedCount = index + 1,
                        statusMessage = "正在生成缩略图 ${index + 1}/${images.size}"
                    )
                }
            }
        }
        AppLog.i("Repository", "generateThumbnails done count=${images.size}")
    }

    private fun decodeSampledBitmap(
        resolver: ContentResolver,
        uri: Uri,
        targetSize: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetSize, targetSize)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return resolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2

        while ((halfWidth / inSampleSize) >= reqWidth && (halfHeight / inSampleSize) >= reqHeight) {
            inSampleSize *= 2
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun deleteDirectoryContents(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteDirectoryContents(file)
            }
            file.delete()
        }
    }

    private fun buildRelativePath(prefix: String, name: String): String {
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun readExifInfo(uri: Uri): ImageExifInfo? {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                ExifMetadataReader.read(input)
            }
        }.getOrNull()
    }

    private fun copyImageToGallery(image: OtgImage) {
        val resolver = appContext.contentResolver
        val sourceUri = Uri.parse(image.documentUri)
        require(sourceUri != Uri.EMPTY) { "当前 PTP 模式只读取了缩略图，不能保存完整原图" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, image.displayName)
            put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OTG Picture Reader")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val targetUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建相册目标文件")

        try {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "无法读取 OTG 图片内容" }
                resolver.openOutputStream(targetUri).use { output ->
                    requireNotNull(output) { "无法写入相册目标文件" }
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(targetUri, values, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(targetUri, null, null)
            throw error
        }
    }

    private fun prepareShareUriInternal(image: OtgImage): Uri {
        val sourceUri = Uri.parse(image.documentUri)
        require(sourceUri != Uri.EMPTY) { "当前 PTP 模式只读取了缩略图，不能分享完整原图" }
        sharedDirectory.mkdirs()
        val safeName = image.displayName.ifBlank { "${image.id}.jpg" }
        val targetFile = File(sharedDirectory, safeName)
        appContext.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "无法读取要分享的图片" }
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            targetFile
        )
    }

    private fun DocumentFile.isImageFile(): Boolean {
        val mime = type.orEmpty()
        val name = name.orEmpty().lowercase(Locale.ROOT)
        return mime.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".heic")
    }

    companion object {
        private const val THUMBNAIL_SIZE_PX = 320
        private const val PTP_SCAN_TIMEOUT_MS = 180_000L
    }
}
