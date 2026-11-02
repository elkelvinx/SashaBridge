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
import kotlinx.coroutines.withContext

/**
 * Small square album covers for lists, such as the play queue: [ThumbnailSources.SIZE_PX]
 * pixels, kept in memory and as WebP files in the app's cache, so each cover is fetched once.
 *
 * The history keeps its covers here too, with [keep]: those go to the app's files instead of
 * its cache, where the trimming of the cache and Android's own cleaning do not reach them,
 * and stay until the history no longer names them ([pruneKept]).
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
    private val keptDir = File(context.applicationContext.filesDir, KEPT_DIR_NAME)
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

    /**
     * Keeps [image], the cover of a track the history names, under [key] until [pruneKept]
     * drops it; afterwards [load] of [key] returns it. Any coroutine; the work runs on
     * [Dispatchers.IO].
     *
     * @param image The cover as an encoded image, such as the JPEG of [AlbumArt].
     */
    suspend fun keep(key: String, image: ByteArray) = withContext(Dispatchers.IO) {
        val file = File(keptDir, fileName(key))
        if (file.exists()) return@withContext
        val cover = squareCover(image) ?: return@withContext
        save(file, cover)
        memory.put(key, cover)
    }

    /**
     * Puts [image], the cover at [artUri], in the cache as if it had been fetched, so it shows
     * at once and without the network. The cache may be emptied, and [load] then fetches it
     * from [artUri] again. Any coroutine; the work runs on [Dispatchers.IO].
     *
     * @param image The cover as an encoded image, such as the JPEG of [AlbumArt].
     */
    suspend fun remember(artUri: String, image: ByteArray) = withContext(Dispatchers.IO) {
        val file = File(dir, fileName(artUri))
        if (file.exists() || memory.get(artUri) != null) return@withContext
        val cover = squareCover(image) ?: return@withContext
        save(file, cover)
        memory.put(artUri, cover)
    }

    /**
     * Deletes the kept covers whose key is not in [keys]. IO thread.
     *
     * @param keys Keys of the covers still named by the history.
     */
    fun pruneKept(keys: Set<String>) {
        val names = keys.mapTo(HashSet(), ::fileName)
        keptDir.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
    }

    /** Reads the cover from the kept covers or the disk cache, or fetches it. IO thread. */
    private suspend fun fetch(artUri: String): Bitmap? {
        val name = fileName(artUri)
        readFile(File(keptDir, name))?.let { kept ->
            memory.put(artUri, kept)
            return kept
        }
        val file = File(dir, name)
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
        read(source)?.let(::squareCover)
    } catch (e: IOException) {
        Log.d(LOG_TAG, "Cannot fetch the cover $source: $e")
        null
    } catch (e: SecurityException) {
        Log.d(LOG_TAG, "No access to the cover $source: $e")
        null
    }

    /** The bytes of [source], a `content://` or `https://` address, or `null` if there are none. */
    private fun read(source: String): ByteArray? =
        if (source.startsWith(ContentResolver.SCHEME_CONTENT)) {
            resolver.openInputStream(source.toUri())?.use { it.readBytes() }
        } else {
            Downloads.bytes(source)
        }

    /** Decodes [bytes] small, crops the middle square and scales it to the list size. */
    private fun squareCover(bytes: ByteArray): Bitmap? {
        val decoded = ImageDecoding.decode(bytes, ThumbnailSources.SIZE_PX, ImageDecoding.Side.SHORTEST) ?: return null
        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        return square.scale(ThumbnailSources.SIZE_PX, ThumbnailSources.SIZE_PX)
    }

    /**
     * Writes [bitmap] as a WebP [file], and trims the oldest covers of the cache when there
     * are too many; kept covers are never trimmed.
     */
    private fun save(file: File, bitmap: Bitmap) {
        try {
            val folder = file.parentFile ?: return
            folder.mkdirs()
            val partial = File(folder, file.name + ".part")
            partial.outputStream().use { bitmap.compress(webp(), WEBP_QUALITY, it) }
            if (!partial.renameTo(file)) partial.delete()
            if (folder == dir) trim()
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

        /** Folder of the fetched covers inside the app's cache. */
        private const val DIR_NAME = "thumbnails"

        /** Folder of the history's covers inside the app's files. */
        private const val KEPT_DIR_NAME = "history-covers"

        /** Memory for covers: about 50 of them. */
        private const val MEMORY_BYTES = 8 * 1024 * 1024

        /** Fetches at once. */
        private const val PARALLEL = 4

        /** Kept covers before trimming, and how many stay after it: a few MB in all. */
        private const val MAX_FILES = 600
        private const val KEEP_FILES = 500

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
