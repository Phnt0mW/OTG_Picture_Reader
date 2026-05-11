package com.example.otg_picture_reader.data

import android.content.Context
import com.example.otg_picture_reader.data.local.OtgCacheStore
import com.example.otg_picture_reader.data.local.OtgImageRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val cacheStore: OtgCacheStore by lazy {
        OtgCacheStore(appContext)
    }

    val imageRepository: OtgImageRepository by lazy {
        OtgImageRepository(appContext, cacheStore)
    }
}
