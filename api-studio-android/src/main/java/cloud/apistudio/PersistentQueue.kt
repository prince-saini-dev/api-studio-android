package cloud.apistudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

/**
 * A FIFO queue of JSON items that survives restarts when [file] is given.
 *
 * Writes are coalesced on [writer]: many pushes in a row cause one file write.
 * [persistNow] writes on the calling thread; the crash handler uses it before the process dies.
 * Files are written to a temp file and renamed, so a crash mid-write never corrupts the queue.
 */
internal class PersistentQueue(
    private val file: File?,
    private val maxSize: Int,
    private val writer: Executor?,
    private val onError: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val items = ArrayList<JSONObject>()
    private var loaded = false
    @Volatile private var writeScheduled = false

    val size: Int get() = synchronized(lock) { load(); items.size }

    /** Loads items saved by an earlier launch, ahead of anything queued since. */
    fun load() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            if (file == null || !file.exists()) return
            try {
                val saved = JSONArray(file.readText())
                val restored = ArrayList<JSONObject>(saved.length() + items.size)
                for (i in 0 until saved.length()) saved.optJSONObject(i)?.let(restored::add)
                restored.addAll(items)
                items.clear()
                items.addAll(restored.takeLast(maxSize))
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }

    fun push(item: JSONObject) {
        synchronized(lock) {
            items.add(item)
            trim()
        }
        scheduleWrite()
    }

    fun pushAll(newItems: List<JSONObject>) {
        if (newItems.isEmpty()) return
        synchronized(lock) {
            load()
            items.addAll(newItems)
            trim()
        }
        scheduleWrite()
    }

    fun peek(count: Int): List<JSONObject> = synchronized(lock) {
        load()
        ArrayList(items.subList(0, minOf(count, items.size)))
    }

    /** Removes the first [count] items (the ones a [peek] returned). */
    fun drop(count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            items.subList(0, minOf(count, items.size)).clear()
        }
        scheduleWrite()
    }

    /** Replaces the first item (used to retry an oversized one without bodies). */
    fun replaceFirst(item: JSONObject) {
        synchronized(lock) { if (items.isNotEmpty()) items[0] = item }
        scheduleWrite()
    }

    fun persistNow() {
        writeScheduled = false
        if (file == null) return
        try {
            val text = synchronized(lock) {
                load() // Never overwrite what an earlier launch saved before it has been read.
                JSONArray(items).toString()
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw java.io.IOException("Could not replace $file")
            }
        } catch (error: Throwable) {
            onError(error)
        }
    }

    private fun trim() {
        if (items.size > maxSize) items.subList(0, items.size - maxSize).clear()
    }

    private fun scheduleWrite() {
        if (file == null || writeScheduled) return
        if (writer == null) {
            persistNow()
            return
        }
        writeScheduled = true
        try {
            writer.execute { if (writeScheduled) persistNow() }
        } catch (error: Throwable) {
            writeScheduled = false
            onError(error)
        }
    }
}
