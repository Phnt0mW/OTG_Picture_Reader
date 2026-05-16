package com.example.otg_picture_reader.data.usb

import kotlinx.coroutines.sync.Mutex

object UsbCameraSessionLock {
    val mutex = Mutex()
}
