package com.example.otg_picture_reader.data

import android.content.Context
import com.example.otg_picture_reader.data.local.OtgCacheStore
import com.example.otg_picture_reader.data.local.OtgImageRepository
import com.example.otg_picture_reader.data.usb.MtpImageScanner
import com.example.otg_picture_reader.data.usb.PtpImageScanner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val cacheStore: OtgCacheStore by lazy {
        OtgCacheStore(appContext)
    }

    val mtpImageScanner: MtpImageScanner by lazy {
        MtpImageScanner(appContext)
    }

    val ptpImageScanner: PtpImageScanner by lazy {
        PtpImageScanner(appContext)
    }

    val imageRepository: OtgImageRepository by lazy {
        OtgImageRepository(appContext, cacheStore, mtpImageScanner, ptpImageScanner)
    }
}
