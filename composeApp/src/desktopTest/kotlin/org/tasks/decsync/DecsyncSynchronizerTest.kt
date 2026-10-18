package org.tasks.decsync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.tasks.caldav.CaldavClientFactory
import org.tasks.data.entity.CaldavAccount
import org.tasks.jobs.BackgroundWork
import org.tasks.mcp.McpGraphTestCase
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*

class DecsyncSynchronizerTest : McpGraphTestCase() {
    private val root = Files.createTempDirectory("tasks-decsync-graph-")
    private lateinit var account: CaldavAccount
    private lateinit var mobile: DecsyncStore
    private val syncer get() = koin.get<DecsyncSynchronizer>()

    @Before fun seed() = runBlocking<Unit> {
        koin.loadModules(listOf(module { single<BackgroundWork> { mock() } }))
        Files.writeString(root.resolve(".decsync-info"), "{\"version\":2}\n")
        account = CaldavAccount(uuid = UUID.randomUUID().toString(), accountType = CaldavAccount.TYPE_DECSYNC, name = "DecSync", url = root.toString())
        account = account.copy(id = db.caldavDao().insert(account))
        mobile = DecsyncStore(root, "tasks-desktop-aaa")
        mobile.setInfo("list", "name", JsonPrimitive("Phone list"))
    }
    @After fun cleanupFolder() { root.toFile().deleteRecursively() }

    @Test fun importEditExportAndRemoteDeletion() = runBlocking {
        mobile.setResource("list", "parent", todo("parent", "From phone"))
        mobile.setResource("list", "child", todo("child", "Subtask", "RELATED-TO;RELTYPE=PARENT:parent\r\n"))
        syncer.sync(account, true)
        assertEquals("", db.caldavDao().getAccount(account.id)?.error)
        val calendar = db.caldavDao().getCalendarsByAccount(account.uuid!!).single()
        val parent = db.caldavDao().getTaskByRemoteId(calendar.uuid!!, "parent")!!
        val child = db.caldavDao().getTaskByRemoteId(calendar.uuid!!, "child")!!
        assertEquals(parent.task, db.taskDao().fetch(child.task)?.parent)
        val task = db.taskDao().fetch(parent.task)!!
        assertEquals("From phone", task.title)
        assertEquals("Test notes", task.notes)
        assertTrue(task.dueDate > 0)
        task.title = "Edited on desktop"
        db.taskDao().update(task, markDirty = true)
        syncer.sync(account, true)
        val exported = mobile.read("list").resources.single { it.path[1] == "parent" }.value.jsonPrimitive.content
        assertTrue(exported.contains("SUMMARY:Edited on desktop"))
        assertTrue(exported.contains("X-REFERENCE-PROPERTY:preserve me"))
        assertFalse(db.dirtyDao().isDirty(parent.id) == true)
        mobile.setResource("list", "child", null)
        syncer.sync(account, true)
        assertNull(db.taskDao().fetch(child.task))
        // Repeating a sync cannot resurrect a deleted task.
        syncer.sync(account, true)
        assertNull(db.caldavDao().getTaskByRemoteId(calendar.uuid!!, "child"))
    }

    @Test fun localDeletionPublishesTombstone() = runBlocking {
        mobile.setResource("list", "gone", todo("gone", "Delete me"))
        syncer.sync(account, true)
        val calendar = db.caldavDao().getCalendarsByAccount(account.uuid!!).single()
        val remote = db.caldavDao().getTaskByRemoteId(calendar.uuid!!, "gone")!!
        val task = db.taskDao().fetch(remote.task)!!
        task.deletionDate = System.currentTimeMillis()
        db.taskDao().update(task, markDirty = true)
        syncer.sync(account, true)
        assertEquals(JsonNull, mobile.read("list").resources.single().value)
        assertNull(db.taskDao().fetch(task.id))
    }

    @Test fun missingFolderDoesNotDeleteImportedTasksOrDiscardEdits() = runBlocking {
        mobile.setResource("list", "uid", todo("uid", "Keep me"))
        syncer.sync(account, true)
        val calendar = db.caldavDao().getCalendarsByAccount(account.uuid!!).single()
        val remote = db.caldavDao().getTaskByRemoteId(calendar.uuid!!, "uid")!!
        val task = db.taskDao().fetch(remote.task)!!
        task.title = "Pending edit"
        db.taskDao().update(task, markDirty = true)
        Files.delete(root.resolve(".decsync-info"))
        syncer.sync(account, true)
        assertEquals("Pending edit", db.taskDao().fetch(task.id)?.title)
        assertTrue(db.dirtyDao().isDirty(remote.id) == true)
        assertFalse(db.caldavDao().getAccount(account.id)?.error.isNullOrBlank())
    }

    @Test fun collectionOperationsUseFolderAndDisconnectKeepsFiles() = runBlocking {
        val factory = koin.get<CaldavClientFactory>()
        val id = factory.forAccount(account).use { it.makeCollection("Desktop list", 0xff315c7a.toInt(), null) }
        assertEquals("Desktop list", mobile.read(id).name)
        factory.forAccount(account, id).use { it.updateCollection("Renamed", 0xff112233.toInt(), null) }
        assertEquals("Renamed", mobile.read(id).name)
        factory.forAccount(account, id).use { it.deleteCollection() }
        assertTrue(mobile.read(id).deleted)
        koin.get<org.tasks.service.TaskDeleter>().delete(account)
        assertTrue(Files.exists(root.resolve(".decsync-info")))
        assertTrue(mobile.read(id).deleted)
    }

    @Test fun localAndRemoteEditsMergeAndPendingEditSurvivesRemoteDeletion() = runBlocking {
        mobile.setResource("list", "uid", todo("uid", "Original"))
        syncer.sync(account, true)
        val calendar = db.caldavDao().getCalendarsByAccount(account.uuid!!).single()
        val remote = db.caldavDao().getTaskByRemoteId(calendar.uuid!!, "uid")!!
        val task = db.taskDao().fetch(remote.task)!!
        task.title = "Offline desktop title"
        db.taskDao().update(task, markDirty = true)
        mobile.setResource("list", "uid", todo("uid", "Original").replace("Test notes", "Mobile notes"))
        syncer.sync(account, true)
        assertEquals("Offline desktop title", db.taskDao().fetch(task.id)?.title)
        assertEquals("Mobile notes", db.taskDao().fetch(task.id)?.notes)
        task.title = "Another local edit"
        task.notes = "Mobile notes"
        db.taskDao().update(task, markDirty = true)
        mobile.setResource("list", "uid", null)
        syncer.sync(account, true)
        assertEquals("Another local edit", db.taskDao().fetch(task.id)?.title)
        assertTrue(mobile.read("list").resources.single().value.jsonPrimitive.content.contains("Another local edit"))
    }

    @Test fun eachInstallationGetsADifferentWriterWhileRestartsKeepTheirIdentity() {
        val identityA = root.resolve("profile-a/writer")
        val identityB = root.resolve("profile-b/writer")
        DecsyncStoreProvider(identityA).forAccount(account).setResource("list", "uid", "first")
        val firstWriters = Files.list(root.resolve("tasks/list/v2")).use { it.map { p -> p.fileName.toString() }.toList() }
        DecsyncStoreProvider(identityA).forAccount(account).setResource("list", "uid", "restart")
        assertEquals(firstWriters.size, Files.list(root.resolve("tasks/list/v2")).use { it.count().toInt() })
        DecsyncStoreProvider(identityB).forAccount(account).setResource("list", "uid", "second installation")
        assertEquals(firstWriters.size + 1, Files.list(root.resolve("tasks/list/v2")).use { it.count().toInt() })
    }

    @Test fun subscriptionRequirementIsPreserved() = runBlocking {
        mobile.setResource("list", "uid", todo("uid", "From phone"))
        syncer.sync(account, false)
        assertTrue(db.caldavDao().getCalendarsByAccount(account.uuid!!).isEmpty())
        assertFalse(db.caldavDao().getAccount(account.id)?.error.isNullOrBlank())
    }

    private fun todo(uid: String, title: String, properties: String = "") = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Tasks tests//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\nSUMMARY:$title\r\nDESCRIPTION:Test notes\r\nDUE;VALUE=DATE:20300101\r\nX-REFERENCE-PROPERTY:preserve me\r\n${properties}END:VTODO\r\nEND:VCALENDAR\r\n"
}
