package com.example.otg_picture_reader

import android.app.Application
import com.example.otg_picture_reader.data.AppContainer
import com.example.otg_picture_reader.util.AppLog

class OtgPictureReaderApp : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }

    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        if (!AppLog.isEnabled) return
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val report = buildString {
                append("线程：")
                append(thread.name)
                append('\n')
                append("异常：")
                append(throwable.javaClass.simpleName)
                append('\n')
                append("信息：")
                append(throwable.message ?: "无")
                append("\n\n")
                append(throwable.stackTraceToString())
            }
            AppLog.exception(
                section = "Crash",
                throwable = throwable,
                message = "uncaught exception on ${thread.name}"
            )
            AppLog.storePendingCrashReport(this, report)
            previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
    }
}
