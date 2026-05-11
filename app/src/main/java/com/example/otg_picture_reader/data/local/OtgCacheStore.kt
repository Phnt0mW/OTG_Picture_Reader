package com.example.otg_picture_reader.data.local

import android.content.Context
import com.example.otg_picture_reader.data.model.OtgImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class OtgCacheStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val indexFile = File(appContext.filesDir, INDEX_FILE_NAME)

    fun saveRootUri(uri: String?) {
        preferences.edit().putString(KEY_ROOT_URI, uri).apply()
    }

    fun readRootUri(): String? = preferences.getString(KEY_ROOT_URI, null)

    fun saveImages(images: List<OtgImage>) {
        indexFile.writeText(gson.toJson(images))
    }

    fun readImages(): List<OtgImage> {
        if (!indexFile.exists()) return emptyList()
        val json = indexFile.readText()
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<OtgImage>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun clearImages() {
        if (indexFile.exists()) {
            indexFile.delete()
        }
    }

    companion object {
        private const val PREFS_NAME = "otg_picture_reader_prefs"
        private const val KEY_ROOT_URI = "root_uri"
        private const val INDEX_FILE_NAME = "otg_index.json"
    }
}
