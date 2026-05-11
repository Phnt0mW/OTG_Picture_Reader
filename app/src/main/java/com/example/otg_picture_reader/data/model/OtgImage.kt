package com.example.otg_picture_reader.data.model

data class OtgImage(
    val id: String,
    val documentUri: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val sortTime: Long,
    val thumbnailPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val exifInfo: ImageExifInfo? = null
)
