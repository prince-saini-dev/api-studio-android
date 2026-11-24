package cloud.apistudio

import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One file per fatal crash, written synchronously by the crash handler and read on the next launch. */
internal class CrashStore(private val dir: File, private val onError: (Throwable) -> Unit) {

    /** Writes on the calling thread, via a temp file and a rename so a half-written crash is never read. */
    fun write(event: JSONObject): Boolean = try {
        dir.mkdirs()
        val name = "crash-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val temp = File(dir, "$name.tmp")
        temp.writeText(event.toString())
        temp.renameTo(File(dir, "$name.json"))
    } catch (error: Throwable) {
        onError(error)
        false
    }

    /** Saved crashes, oldest first. Unreadable files are deleted. */
    fun pending(): List<Pair<File, JSONObject>> {
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        return files.filter { it.name.endsWith(".json") }.sortedBy { it.name }.mapNotNull { file ->
            try {
                file to JSONObject(file.readText())
            } catch (error: Throwable) {
                onError(error)
                file.delete()
                null
            }
        }
    }

    fun delete(files: List<File>) {
        files.forEach { it.delete() }
    }
}

/**
 * Records an uncaught exception to disk, then hands it to the handler that was installed before
 * (on Android that one shows the crash dialog and kills the process). Never waits for the network.
 */
internal class ApiStudioCrashHandler(
    /** Resolved at crash time, so a second ApiStudio.init still gets its crashes recorded. */
    private val client: () -> ApiStudioClient?,
    internal val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    private val handling = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Only the first crash is recorded; a second thread crashing meanwhile goes straight on.
        if (handling.compareAndSet(false, true)) {
            try {
                client()?.recordCrash(thread, throwable)
            } catch (_: Throwable) {
                // The SDK must never get in the way of the real crash.
            }
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable)
        } else {
            System.err.println("Exception in thread \"${thread.name}\" ")
            throwable.printStackTrace()
        }
    }

    companion object {
        fun install(client: () -> ApiStudioClient?): ApiStudioCrashHandler? {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is ApiStudioCrashHandler) return null // Already installed (init called twice).
            return ApiStudioCrashHandler(client, current).also { Thread.setDefaultUncaughtExceptionHandler(it) }
        }
    }
}
