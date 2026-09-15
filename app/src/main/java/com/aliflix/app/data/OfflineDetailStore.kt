package com.aliflix.app.data

import android.content.Context
import android.util.AtomicFile
import com.aliflix.app.model.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Durable title metadata is independent of the disposable image/network cache. */
internal class OfflineDetailStore(context: Context) {
    private val directory = File(context.filesDir, "offline-details")
    suspend fun load(item: Media): Media? = withContext(Dispatchers.IO) {
        runCatching { Media.fromJson(JSONObject(AtomicFile(file(item)).openRead().bufferedReader().use { it.readText() })) }.getOrNull()
    }
    suspend fun save(item: Media) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = AtomicFile(file(item))
        val stream = target.startWrite()
        try { stream.write(item.toJson().toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (error: Exception) { target.failWrite(stream); throw error }
    }
    private fun file(item: Media) = File(directory, "${item.type.routeName}-${item.id}.json")
}
