package dev.andrewarrow.cubacadabra.game

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Persistent cache shared by the morph editor preview and game loading. */
internal class MorphPackCache(context: Context) {
    private companion object {
        const val DIRECTORY_NAME = "MorphPreviewPacks-v1"
        const val TAG = "MorphPackCache"
        val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
    }

    private val directory = File(context.applicationContext.cacheDir, DIRECTORY_NAME)

    fun read(url: URL, maximumBytes: Int): ByteArray? {
        val path = cacheFile(url) ?: return null
        return runCatching {
            path.takeIf { it.isFile }?.readBytes()?.takeIf {
                it.isNotEmpty() && it.size <= maximumBytes
            }
        }.getOrNull()
    }

    fun write(url: URL, data: ByteArray) {
        val path = cacheFile(url) ?: return
        runCatching {
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return@runCatching
            val temporary = File.createTempFile("morph-pack-", ".tmp", directory)
            try {
                temporary.writeBytes(data)
                if (!temporary.renameTo(path)) path.writeBytes(data)
            } finally {
                temporary.delete()
            }
        }.onFailure {
            Log.d(TAG, "morph pack cache write skipped: ${it.message}")
        }
    }

    private fun cacheFile(url: URL): File? {
        val hash = url.path.substringAfterLast('/').removeSuffix(".morphpack")
        if (!HASH_PATTERN.matches(hash)) return null
        return File(directory, "$hash.morphpack")
    }

}

internal fun fetchMorphPack(url: URL, cache: MorphPackCache, maximumBytes: Int): ByteArray {
    cache.read(url, maximumBytes)?.let {
        Log.d("MorphPackCache", "morph pack cache hit hash=${url.path.substringAfterLast('/')}")
        return it
    }
    if (url.protocol !in setOf("http", "https") || url.host.isNullOrEmpty()) {
        throw IllegalArgumentException("The morph pack URL must use HTTP or HTTPS.")
    }
    val connection = (url.openConnection() as? HttpURLConnection)
        ?: throw IllegalArgumentException("The morph pack URL must use HTTP or HTTPS.")
    connection.connectTimeout = 10_000
    connection.readTimeout = 20_000
    connection.requestMethod = "GET"
    connection.connect()
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("The morph pack server returned HTTP ${connection.responseCode}.")
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty() || bytes.size > maximumBytes) {
            throw IllegalStateException("The morph pack is invalid or too large.")
        }
        cache.write(url, bytes)
        return bytes
    } finally {
        connection.disconnect()
    }
}
