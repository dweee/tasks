package org.tasks.decsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.tasks.data.dao.CaldavDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.sync.SyncAdapters
import org.tasks.sync.SyncSource
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Polling survives Syncthing's atomic directory/file replacements on both desktop platforms. */
class DecsyncFolderMonitor(private val dao: CaldavDao, private val syncAdapters: SyncAdapters) {
    suspend fun run() {
        var previous = emptyMap<Long, List<String>>()
        while (true) {
            val current = withContext(Dispatchers.IO) {
                dao.getAccounts(CaldavAccount.TYPE_DECSYNC).associate { account ->
                    account.id to signature(Path.of(requireNotNull(account.url)))
                }
            }
            if (current.isNotEmpty() && current != previous) syncAdapters.sync(SyncSource.CONTENT_OBSERVER)
            previous = current
            delay(30_000)
        }
    }

    private fun signature(root: Path): List<String> = try {
        check(Files.isRegularFile(root.resolve(".decsync-info"), NOFOLLOW_LINKS))
        val tasks = root.resolve("tasks")
        if (!Files.isDirectory(tasks, NOFOLLOW_LINKS)) listOf("empty") else Files.walk(tasks).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && !it.fileName.toString().startsWith(".") }
                .sorted().map { "${tasks.relativize(it)}:${Files.size(it)}:${Files.getLastModifiedTime(it)}" }.toList()
        }
    } catch (_: Exception) { listOf("unavailable") }
}
