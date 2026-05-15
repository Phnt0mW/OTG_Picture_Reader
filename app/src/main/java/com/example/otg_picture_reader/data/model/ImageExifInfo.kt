package com.example.otg_picture_reader.data.model

data class ImageExifInfo(
    val takenTime: Long? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val focalLengthMm: String? = null,
    val focalLength35Mm: String? = null,
    val lensSpecification: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
    val iso: String? = null,
    val width: Int? = null,
    val height: Int? = null
)
