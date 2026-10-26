package com.aliflix.app.talk

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only exact, approved synthetic bridge sentences enter this bounded cache.
 * Never stores microphone input, user text or free-form assistant speech. */
internal class TalkBridgeAudioCache(private val directory:File) {
    private fun key(voice:String,text:String)=MessageDigest.getInstance("SHA-256")
        .digest("gemini-3.8-live-v1\u0000$voice\u0000$text".toByteArray())
        .joinToString(""){"%02x".format(it)}+".pcm"
    suspend fun load(voice:String,text:String):ByteArray?=withContext(Dispatchers.IO) {
        runCatching {
            val file=File(directory,key(voice,text))
            if(file.length() !in 2L..480000L||file.length()%2L!=0L)null else file.readBytes().also{file.setLastModified(System.currentTimeMillis())}
        }.getOrNull()
    }
    suspend fun store(voice:String,text:String,chunks:List<ByteArray>)=withContext(Dispatchers.IO) {
        runCatching {
            val count=chunks.sumOf{it.size}
            if(count !in 2..480000||chunks.any{it.size%2!=0})return@runCatching
            directory.mkdirs()
            val target=File(directory,key(voice,text))
            val temp=File(directory,target.name+"."+java.util.UUID.randomUUID()+".tmp")
            try {
                temp.outputStream().use{out->chunks.forEach{out.write(it)}}
                Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
                directory.listFiles()?.filter{it.extension=="pcm"}?.sortedByDescending{it.lastModified()}?.drop(10)?.forEach{it.delete()}
            } finally {temp.delete()}
        }
        Unit
    }
}
