package io.github.elkelvinx.sashabridge.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.core.net.toUri
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Small square album covers for lists, such as the play queue: [ThumbnailSources.SIZE_PX]
 * pixels, kept in memory and as WebP files in the app's cache, so each cover is fetched once.
 * The history will keep its covers here too.
 *
 * Covers come from the addresses of [ThumbnailSources]: a player's own content URI or an
 * HTTPS address. A cover that cannot be fetched is `null`; the list shows a placeholder.
 *
 * One process-wide instance, [get]. [cached] is quick enough for the main thread; [load]
 * works on [Dispatchers.IO], at most [PARALLEL] fetches at once and once per cover however
 * many rows ask for it.
 */
class ThumbnailStore private constructor(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val dir = File(context.applicationContext.cacheDir, DIR_NAME)
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Fetches outlive the screen that asked: a cover half downloaded is still worth keeping. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fetches in progress, by art address. */
    private val loading = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    private val fetches = Semaphore(PARALLEL)

    /**
     * The cover of [artUri] if it is in memory, without waiting. Any thread.
     */
    fun cached(artUri: String): Bitmap? = memory.get(artUri)

    /**
     * The cover of [artUri]: from memory, from the disk cache, or fetched and kept. Any
     * coroutine; the work runs on [Dispatchers.IO].
     *
     * @return The [ThumbnailSources.SIZE_PX] square cover, or `null` if it cannot be fetched.
     */
    suspend fun load(artUri: String): Bitmap? {
        memory.get(artUri)?.let { return it }
        val task = loading.computeIfAbsent(artUri) {
            // Lazy, so it can only finish, and leave the map, after it is in the map.
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    fetch(artUri)
                } finally {
                    loading.remove(artUri)
                }
            }
        }
        return task.await()
    }

    /** Reads the cover from the disk cache, or fetches it and keeps it. IO thread. */
    private suspend fun fetch(artUri: String): Bitmap? {
        val file = File(dir, fileName(artUri))
        val bitmap = readFile(file) ?: fetches.withPermit {
            ThumbnailSources.candidates(artUri).firstNotNullOfOrNull(::download)
        }?.also { save(file, it) } ?: return null
        memory.put(artUri, bitmap)
        return bitmap
    }

    /** A kept cover, marked as just used, or `null`. */
    private fun readFile(file: File): Bitmap? {
        if (!file.exists()) return null
        file.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(file.path)
    }

    /** Downloads or reads [source] and makes it a square cover, or `null` on any failure. */
    private fun download(source: String): Bitmap? = try {
        open(source)?.use { it.readBytes() }?.let(::squareCover)
    } catch (e: IOException) {
        Log.d(LOG_TAG, "Cannot fetch the cover $source: $e")
        null
    } catch (e: SecurityException) {
        Log.d(LOG_TAG, "No access to the cover $source: $e")
        null
    }

    /** Opens [source], a `content://` or `https://` address. */
    private fun open(source: String): InputStream? {
        if (source.startsWith(ContentResolver.SCHEME_CONTENT)) return resolver.openInputStream(source.toUri())
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            return null
        }
        return connection.inputStream
    }

    /** Decodes [bytes] small, crops the middle square and scales it to the list size. */
    private fun squareCover(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ThumbnailSources.SIZE_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        return square.scale(ThumbnailSources.SIZE_PX, ThumbnailSources.SIZE_PX)
    }

    /** Keeps [bitmap] as a WebP file, and trims the oldest covers when there are too many. */
    private fun save(file: File, bitmap: Bitmap) {
        try {
            dir.mkdirs()
            val partial = File(dir, file.name + ".part")
            partial.outputStream().use { bitmap.compress(webp(), WEBP_QUALITY, it) }
            if (!partial.renameTo(file)) partial.delete()
            trim()
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Cannot keep the cover ${file.name}", e)
        }
    }

    /** Deletes the least recently used covers beyond [MAX_FILES], down to [KEEP_FILES]. */
    private fun trim() {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP_FILES).forEach { it.delete() }
    }

    /** File name of the cover of [artUri]: a hash, since the address is long and has slashes. */
    private fun fileName(artUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(artUri.toByteArray(Charsets.UTF_8))
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) } + ".webp"
    }

    /** Lossy WebP, the format of the kept covers. */
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    companion object {

        /** Folder of the kept covers inside the app's cache. */
        private const val DIR_NAME = "thumbnails"

        /** Memory for covers: about 50 of them. */
        private const val MEMORY_BYTES = 8 * 1024 * 1024

        /** Fetches at once. */
        private const val PARALLEL = 4

        /** Kept covers before trimming, and how many stay after it: a few MB in all. */
        private const val MAX_FILES = 600
        private const val KEEP_FILES = 500

        /** Connection and read timeout of a download. */
        private const val TIMEOUT_MS = 8_000

        /** Quality of the kept WebP files. */
        private const val WEBP_QUALITY = 85

        /** Bytes of the hash in a file name. */
        private const val HASH_BYTES = 16

        @Volatile
        private var instance: ThumbnailStore? = null

        /**
         * Returns the process-wide instance, creating it on first use. Any thread.
         *
         * @param context Any context; only the application context is kept.
         */
        fun get(context: Context): ThumbnailStore = instance ?: synchronized(this) {
            instance ?: ThumbnailStore(context).also { instance = it }
        }
    }
}
