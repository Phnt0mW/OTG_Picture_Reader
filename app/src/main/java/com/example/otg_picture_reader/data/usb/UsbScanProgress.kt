package com.example.otg_picture_reader.data.usb

import com.example.otg_picture_reader.data.model.OtgImage

data class UsbScanProgress(
    val scannedCount: Int,
    val indexedCount: Int,
    val totalCount: Int? = null,
    val currentName: String? = null,
    val indexedImage: OtgImage? = null,
    val phase: UsbScanPhase = UsbScanPhase.SCANNING
)

enum class UsbScanPhase {
    READING_TIME,
    SCANNING
}
