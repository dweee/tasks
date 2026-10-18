package org.tasks.decsync

import org.tasks.data.entity.CaldavAccount
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

/** Installation identity lives outside database backups and contains no device information. */
class DecsyncStoreProvider(identityFile: Path) {
    private val writer: String
    init {
        Files.createDirectories(identityFile.parent)
        try { Files.writeString(identityFile, UUID.randomUUID().toString(), CREATE_NEW) }
        catch (_: FileAlreadyExistsException) { /* Keep the installation's existing identity. */ }
        val id = Files.readString(identityFile).trim()
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid DecSync installation identity." }
        writer = "tasks-desktop-$id"
    }
    fun forAccount(account: CaldavAccount) = DecsyncStore(Path.of(requireNotNull(account.url)), writer)
}
