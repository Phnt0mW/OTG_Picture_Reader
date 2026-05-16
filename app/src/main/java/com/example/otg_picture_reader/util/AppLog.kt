package com.example.otg_picture_reader.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "OTGReader"
    private const val LOG_FILE_NAME = "app.log"
    private const val PREFS_NAME = "app_log_settings"
    private const val KEY_LOG_MODE = "log_mode"
    private const val KEY_LOG_TREE_URI = "log_tree_uri"
    private const val KEY_LOG_DOCUMENT_URI = "log_document_uri"
    private const val KEY_MAX_LOG_ENTRIES = "max_log_entries"
    private const val PREFS_CRASH_NAME = "app_crash_report"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val MODE_INTERNAL = "internal"
    private const val MODE_EXTERNAL = "external"
    private const val DEFAULT_MAX_LOG_ENTRIES = 2_000
    private const val MIN_MAX_LOG_ENTRIES = 100
    private const val MAX_MAX_LOG_ENTRIES = 20_000
    private const val TRIM_BATCH_SIZE = 100
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val writeLock = Any()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var logFile: File? = null
    @Volatile
    private var logDocumentUri: Uri? = null
    @Volatile
    private var maxLogEntries: Int = DEFAULT_MAX_LOG_ENTRIES

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_LOG_MODE, null)
        val treeUri = prefs.getString(KEY_LOG_TREE_URI, null)
        val documentUri = prefs.getString(KEY_LOG_DOCUMENT_URI, null)
        maxLogEntries = prefs.getInt(KEY_MAX_LOG_ENTRIES, DEFAULT_MAX_LOG_ENTRIES)
            .coerceIn(MIN_MAX_LOG_ENTRIES, MAX_MAX_LOG_ENTRIES)

        if (mode == MODE_EXTERNAL) {
            if (treeUri != null) {
                val restored = useExternalDirectory(
                    context = context,
                    treeUri = Uri.parse(treeUri),
                    persist = false,
                    existingDocumentUri = documentUri?.let(Uri::parse)
                )
                if (restored.isSuccess) {
                    logI("AppLog", "initialized external log target: ${recentLogPath()}")
                    return
                }
            }
            clearPersistedMode(context)
        }

        val persistInternalMode = mode == MODE_INTERNAL
        useInternalDirectory(context, persist = persistInternalMode)
        logI(
            "AppLog",
            if (persistInternalMode) {
                "initialized internal log target: ${recentLogPath()}"
            } else {
                "initialized temporary internal log target pending user selection: ${recentLogPath()}"
            }
        )
    }

    fun shouldPromptForLogLocation(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOG_MODE, null) == null
    }

    fun currentMaxLogEntries(): Int = maxLogEntries

    fun setMaxLogEntries(context: Context, maxEntries: Int): Int {
        val normalized = maxEntries.coerceIn(MIN_MAX_LOG_ENTRIES, MAX_MAX_LOG_ENTRIES)
        maxLogEntries = normalized
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_LOG_ENTRIES, normalized)
            .apply()
        trimCurrentLog(force = true)
        return normalized
    }

    fun useInternalDirectory(context: Context, persist: Boolean = true): String {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        logDocumentUri = null
        logFile = File(dir, LOG_FILE_NAME)
        if (persist) {
            persistMode(context, MODE_INTERNAL, treeUri = null, documentUri = null)
        }
        trimCurrentLog(force = true)
        return logFile?.absolutePath.orEmpty()
    }

    fun useExternalDirectory(
        context: Context,
        treeUri: Uri,
        persist: Boolean = true,
        existingDocumentUri: Uri? = null
    ): Result<String> {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return Result.failure(IllegalStateException("无法访问所选目录"))
        val existing = existingDocumentUri?.let { uri ->
            DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() && it.canWrite() }
        } ?: tree.listFiles().firstOrNull { it.name == LOG_FILE_NAME }
        val logDocument = existing ?: tree.createFile("text/plain", LOG_FILE_NAME)
        return if (logDocument != null) {
            logFile = null
            logDocumentUri = logDocument.uri
            if (persist) {
                persistMode(
                    context = context,
                    mode = MODE_EXTERNAL,
                    treeUri = treeUri.toString(),
                    documentUri = logDocument.uri.toString()
                )
            }
            trimCurrentLog(force = true)
            Result.success(logDocument.uri.toString())
        } else {
            Result.failure(IllegalStateException("无法在所选目录创建日志文件"))
        }
    }

    fun d(section: String, message: String) = write(Log.DEBUG, section, message, null)
    fun i(section: String, message: String) = write(Log.INFO, section, message, null)
    fun w(section: String, message: String, throwable: Throwable? = null) = write(Log.WARN, section, message, throwable)
    fun e(section: String, message: String, throwable: Throwable? = null) = write(Log.ERROR, section, message, throwable)

    fun exception(section: String, throwable: Throwable, message: String? = null) {
        write(Log.ERROR, section, message ?: throwable.message.orEmpty(), throwable)
    }

    fun storePendingCrashReport(context: Context, report: String) {
        context.getSharedPreferences(PREFS_CRASH_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CRASH, report)
            .apply()
    }

    fun consumePendingCrashReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_CRASH_NAME, Context.MODE_PRIVATE)
        val report = prefs.getString(KEY_LAST_CRASH, null)
        if (report != null) {
            prefs.edit().remove(KEY_LAST_CRASH).apply()
        }
        return report
    }

    fun recentLogPath(): String? = logFile?.absolutePath ?: logDocumentUri?.toString()

    private fun logI(section: String, message: String) = write(Log.INFO, section, message, null)

    private fun write(priority: Int, section: String, message: String, throwable: Throwable?) {
        val line = synchronized(writeLock) { buildLine(section, message) }
        when (priority) {
            Log.DEBUG -> Log.d(TAG, line, throwable)
            Log.INFO -> Log.i(TAG, line, throwable)
            Log.WARN -> Log.w(TAG, line, throwable)
            else -> Log.e(TAG, line, throwable)
        }
        appendToFile(priority, line, throwable)
    }

    private fun buildLine(section: String, message: String): String {
        val timestamp = timestampFormat.format(Date())
        return "[$timestamp][$section] $message"
    }

    private fun appendToFile(priority: Int, line: String, throwable: Throwable?) {
        synchronized(writeLock) {
            val entry = buildFileEntry(priority, line, throwable)
            val file = logFile
            val uri = logDocumentUri
            if (file != null) {
                runCatching {
                    file.appendText(entry)
                    trimFileIfNeeded(file)
                }
                return
            }
            if (uri != null) {
                val context = appContext ?: return
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "wa")?.use { pfd ->
                        FileOutputStream(pfd.fileDescriptor).use { output ->
                            output.write(entry.toByteArray())
                            output.flush()
                        }
                    }
                    trimDocumentIfNeeded(context, uri)
                }
            }
        }
    }

    private fun buildFileEntry(priority: Int, line: String, throwable: Throwable?): String {
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            else -> "E"
        }
        val stack = throwable?.stackTraceToString()?.let { "\n$it" }.orEmpty()
        return "$level $line$stack\n"
    }

    private fun trimCurrentLog(force: Boolean) {
        synchronized(writeLock) {
            logFile?.let { file ->
                runCatching { trimFileIfNeeded(file, force) }
                return
            }
            val context = appContext ?: return
            val uri = logDocumentUri ?: return
            runCatching { trimDocumentIfNeeded(context, uri, force) }
        }
    }

    private fun trimFileIfNeeded(file: File, force: Boolean = false) {
        if (!file.exists()) return
        val text = file.readText()
        val trimmed = trimLogText(text, force) ?: return
        file.writeText(trimmed)
    }

    private fun trimDocumentIfNeeded(context: Context, uri: Uri, force: Boolean = false) {
        val resolver = context.contentResolver
        val text = resolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().use { it.readText() }
        } ?: return
        val trimmed = trimLogText(text, force) ?: return
        resolver.openFileDescriptor(uri, "rwt")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { output ->
                output.write(trimmed.toByteArray())
                output.flush()
            }
        }
    }

    private fun trimLogText(text: String, force: Boolean): String? {
        if (text.isEmpty()) return null
        val entryStarts = logEntryStartRegex.findAll(text).map { it.range.first }.toList()
        val limit = maxLogEntries
        val trimThreshold = if (force) limit else limit + TRIM_BATCH_SIZE
        if (entryStarts.size <= trimThreshold) return null
        val keepFrom = entryStarts[entryStarts.size - limit]
        return text.substring(keepFrom)
    }

    private fun persistMode(context: Context, mode: String, treeUri: String?, documentUri: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOG_MODE, mode)
            .putString(KEY_LOG_TREE_URI, treeUri)
            .putString(KEY_LOG_DOCUMENT_URI, documentUri)
            .apply()
    }

    private fun clearPersistedMode(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LOG_MODE)
            .remove(KEY_LOG_TREE_URI)
            .remove(KEY_LOG_DOCUMENT_URI)
            .apply()
    }

    private val logEntryStartRegex = Regex("(?m)^[DIWE] \\[\\d{4}-\\d{2}-\\d{2} ")
}
