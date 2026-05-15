package com.example.otg_picture_reader.data.usb

import java.util.Locale

fun cameraImageId(
    displayName: String,
    sizeBytes: Long,
    modifiedTime: Long?,
    width: Int,
    height: Int
): String {
    val normalizedName = displayName.ifBlank { "unnamed" }
        .trim()
        .lowercase(Locale.ROOT)
    val timePart = modifiedTime?.takeIf { it > 0L } ?: 0L
    return "camera_${normalizedName}_${sizeBytes.coerceAtLeast(0L)}_${timePart}"
        .replace(Regex("[^a-z0-9._-]+"), "_")
}
