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
import androidx.exifinterface.media.ExifInterface
import com.example.otg_picture_reader.data.model.ImageExifInfo
import com.example.otg_picture_reader.data.model.OtgImage
import com.example.otg_picture_reader.data.model.ScanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class OtgImageRepository(
    context: Context,
    private val cacheStore: OtgCacheStore
) {
    private val appContext = context.applicationContext
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val thumbnailDirectory = File(appContext.cacheDir, "thumbnails").apply { mkdirs() }
    private val sharedDirectory = File(appContext.cacheDir, "shared").apply { mkdirs() }

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

        val updated = images.toMutableList()
        var changed = false

        images.forEachIndexed { index, image ->
            val thumbnailFile = File(thumbnailDirectory, "${image.id}.jpg")
            if (thumbnailFile.exists()) {
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
        }
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
                val exif = ExifInterface(input)
                ImageExifInfo(
                    takenTime = readTakenTime(exif),
                    cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE),
                    cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL),
                    lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
                    focalLengthMm = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "$it mm" },
                    aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                    shutterSpeed = formatExposure(exif),
                    iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                        ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
                    width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 },
                    height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                )
            }
        }.getOrNull()
    }

    private fun readTakenTime(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        return runCatching { format.parse(raw)?.time }.getOrNull()
    }

    private fun formatExposure(exif: ExifInterface): String? {
        val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
        if (exposureTime <= 0.0) return null
        return if (exposureTime >= 1.0) {
            String.format(Locale.US, "%.1f s", exposureTime)
        } else {
            val denominator = kotlin.math.round(1.0 / exposureTime).toInt().coerceAtLeast(1)
            "1/$denominator s"
        }
    }

    private fun copyImageToGallery(image: OtgImage) {
        val resolver = appContext.contentResolver
        val sourceUri = Uri.parse(image.documentUri)
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
        sharedDirectory.mkdirs()
        val safeName = image.displayName.ifBlank { "${image.id}.jpg" }
        val targetFile = File(sharedDirectory, safeName)
        appContext.contentResolver.openInputStream(Uri.parse(image.documentUri)).use { input ->
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
    }
}
