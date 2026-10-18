package org.tasks.decsync

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.*

class DecsyncStoreTest {
    private lateinit var root: Path
    private val desktop get() = DecsyncStore(root, "tasks-desktop-aaa")
    private val phone get() = DecsyncStore(root, "tasks-desktop-bbb")

    @BeforeTest fun setup() {
        root = Files.createTempDirectory("decsync-test-")
        Files.writeString(root.resolve(".decsync-info"), "{\"version\":2}\n")
    }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun importsUnmodifiedLibdecsyncFixture() {
        val source = Path.of(requireNotNull(javaClass.getResource("/decsync/.decsync-info")).toURI()).parent
        source.toFile().copyRecursively(root.toFile(), overwrite = true)
        val collection = desktop.collections().single()
        assertEquals("Phone tasks", collection.name)
        assertEquals("#315c7a", collection.color)
        assertEquals("reference-uid", collection.resources.single().path[1])
        assertTrue(collection.resources.single().value.jsonPrimitive.content.contains("SUMMARY:From DecSync CC format"))
        val file = root.resolve("tasks/personal/v2/reference-mobile/1d")
        val peerData = Files.readString(file)
        desktop.setResource("personal", "reference-uid", "desktop edit")
        assertEquals(peerData, Files.readString(file))
    }

    @Test fun upstreamHashVectors() {
        // libdecsync's published HashTest vectors, independent of this implementation.
        assertEquals("e2", DecsyncStore.hash(listOf("foo", "bar", "baz")))
        assertEquals("b9", DecsyncStore.hash(listOf("feeds", "subscriptions")))
        assertEquals("07", DecsyncStore.hash(listOf("entries", "read", "2020", "08", "23")))
        assertEquals("info", DecsyncStore.hash(listOf("info")))
    }

    @Test fun readsCcWireFormatAndLateFilesWithoutSequenceChange() {
        val app = root.resolve("tasks/personal/v2/mobile").also { Files.createDirectories(it) }
        Files.writeString(app.resolve("info"), "[[\"info\"],\"2024-01-02T03:04:05\",\"name\",\"Personal\"]\n")
        Files.writeString(app.resolve("sequences"), "{\"info\":1,\"e2\":1}")
        assertEquals("Personal", desktop.collections().single().name)
        // A bucket arrives after its sequence file, a normal Syncthing delivery order.
        Files.writeString(app.resolve("e2"), "[[\"foo\",\"bar\",\"baz\"],\"2024-01-02T03:04:05\",null,\"late value\"]\n")
        assertEquals(JsonPrimitive("late value"), desktop.read("personal").entries.single { it.path == listOf("foo", "bar", "baz") }.value)
    }

    @Test fun bothWritersEditDeleteAndRetainTombstonesAcrossRestarts() {
        desktop.setInfo("list", "name", JsonPrimitive("Tasks"))
        desktop.setResource("list", "uid", "first")
        val original = phone.read("list").resources.single()
        phone.setResource("list", "uid", "mobile edit")
        val edited = desktop.read("list").resources.single()
        assertTrue(edited.datetime > original.datetime)
        assertEquals(JsonPrimitive("mobile edit"), edited.value)
        desktop.setResource("list", "uid", null)
        val snapshot = phone.read("list")
        assertEquals(JsonNull, snapshot.resources.single().value)
        phone.retain(snapshot)
        root.resolve("tasks/list/v2/tasks-desktop-aaa").toFile().deleteRecursively()
        assertEquals(JsonNull, phone.read("list").resources.single().value)
    }

    @Test fun retryAfterBucketWrittenButSequenceMissingReannounces() {
        desktop.setResource("list", "uid", "task")
        val own = root.resolve("tasks/list/v2/tasks-desktop-aaa")
        Files.delete(own.resolve("sequences"))
        desktop.setResource("list", "uid", "task")
        assertTrue(Files.readString(own.resolve("sequences")).contains(DecsyncStore.hash(listOf("resources", "uid"))))
        assertEquals(1, desktop.read("list").resources.size)
    }

    @Test fun staleSnapshotCannotOverwriteNewLocalEdit() {
        desktop.setResource("list", "uid", "before")
        val stale = desktop.read("list")
        desktop.setResource("list", "uid", "after")
        desktop.retain(stale)
        assertEquals(JsonPrimitive("after"), desktop.read("list").resources.single().value)
    }

    @Test fun partialAndLegacyFilesFailWithoutWriting() {
        val app = root.resolve("tasks/list/v2/mobile").also { Files.createDirectories(it) }
        Files.writeString(app.resolve("info"), "[[\"info\"],\"2024-01-02T03:04:05\",\"name\",\"Incomplete\"]")
        assertFails { desktop.collections() }
        assertFalse(Files.exists(root.resolve("tasks/list/v2/tasks-desktop-aaa")))
        app.toFile().deleteRecursively()
        root.resolve("tasks/list/v2").toFile().deleteRecursively()
        Files.createDirectories(root.resolve("tasks/list/new-entries/mobile"))
        assertFails { desktop.collections() }
        Files.writeString(root.resolve(".decsync-info"), "{\"version\":1}")
        assertFails { desktop.setResource("list", "uid", "never written") }
    }

    @Test fun missingRootAndTraversalDoNotCreateDirectories() {
        assertFails { desktop.read("../outside") }
        assertFails { desktop.read("nested/list") }
        val missing = root.resolve("missing")
        assertFails { DecsyncStore(missing, "tasks-desktop-aaa").setResource("list", "uid", "task") }
        assertFalse(Files.exists(missing))
    }

    @Test fun localParallelEditsPreserveAllTasks() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            (0..19).map { number -> executor.submit { desktop.setResource("list", "task-$number", "value-$number") } }.forEach { it.get() }
            assertEquals(20, desktop.read("list").resources.size)
        } finally { executor.shutdownNow() }
    }
}
