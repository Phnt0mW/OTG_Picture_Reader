package com.example.otg_picture_reader.data.model

data class ScanState(
    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val indexedCount: Int = 0,
    val statusMessage: String = "请选择 OTG 目录后开始扫描。"
)
