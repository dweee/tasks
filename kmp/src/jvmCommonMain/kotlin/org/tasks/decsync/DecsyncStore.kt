package org.tasks.decsync

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** DecSync v2 task collections. Only our random writer's directory is ever modified.
 * Spec: https://github.com/39aldo39/DecSync/blob/master/spec-v2.md
 * A full scan deliberately ignores sequence caches: Syncthing may deliver sequences first.
 */
class DecsyncStore(val root: Path, private val writer: String) {
    data class Entry(val path: List<String>, val datetime: String, val key: JsonElement, val value: JsonElement) {
        fun json() = buildJsonArray {
            add(JsonArray(path.map(::JsonPrimitive)))
            add(datetime)
            add(key)
            add(value)
        }
        val identity get() = path to key
    }
    data class Collection(val id: String, val entries: List<Entry>) {
        fun info(key: String): JsonElement? = entries.find { it.path == listOf("info") && it.key == JsonPrimitive(key) }?.value
        val deleted get() = info("deleted") == JsonPrimitive(true)
        val name get() = (info("name") as? JsonPrimitive)?.contentOrNull ?: "DecSync tasks"
        val color get() = (info("color") as? JsonPrimitive)?.contentOrNull
        val resources get() = entries.filter { it.path.size == 2 && it.path[0] == "resources" && it.key == JsonNull }
    }

    init { require(writer.matches(Regex("tasks-desktop-[a-f0-9-]+"))) }

    fun validate() {
        check(Files.isDirectory(root)) { "DecSync folder is unavailable. Restore the synced folder and try again." }
        val info = root.resolve(".decsync-info")
        check(Files.isRegularFile(info, NOFOLLOW_LINKS)) { "Choose the DecSync root folder containing .decsync-info." }
        val version = Json.parseToJsonElement(Files.readString(info)).jsonObject["version"]?.jsonPrimitive?.intOrNull
        check(version == 2) { "This desktop adapter requires DecSync v2. Upgrade the folder with DecSync CC first." }
    }

    fun collections(): List<Collection> {
        validate()
        val tasks = root.resolve("tasks")
        if (!Files.exists(tasks, NOFOLLOW_LINKS)) return emptyList()
        check(Files.isDirectory(tasks, NOFOLLOW_LINKS)) { "Invalid DecSync task directory." }
        return Files.list(tasks).use { paths ->
            paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && !it.fileName.toString().startsWith(".") }
                .sorted().map { read(it.fileName.toString()) }.toList()
        }
    }

    fun read(collection: String): Collection {
        validate()
        val directory = collectionPath(collection)
        val v2 = directory.resolve("v2")
        // Never silently import an incomplete legacy collection as an empty list.
        check(!Files.exists(directory.resolve("new-entries")) || Files.isDirectory(v2, NOFOLLOW_LINKS)) {
            "A task list still uses DecSync v1. Sync it with an updated DecSync CC before connecting it here."
        }
        val entries = linkedMapOf<Pair<List<String>, JsonElement>, Entry>()
        if (Files.exists(v2, NOFOLLOW_LINKS)) {
            check(Files.isDirectory(v2, NOFOLLOW_LINKS)) { "Invalid DecSync data directory." }
            Files.list(v2).use { apps ->
                apps.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted().forEach { app ->
                    Files.list(app).use { files ->
                        files.filter { it.fileName.toString().matches(Regex("info|[0-9a-f]{2}")) }.sorted().forEach { file ->
                            readFile(file).forEach { entry ->
                                check(hash(entry.path) == file.fileName.toString()) { "Invalid DecSync entry bucket." }
                                val old = entries[entry.identity]
                                if (old == null || entry.datetime > old.datetime) entries[entry.identity] = entry
                            }
                        }
                    }
                }
            }
        }
        return Collection(collection, entries.values.toList())
    }

    /** Preserve received values and tombstones locally without announcing them as local edits. */
    fun retain(collection: Collection) = synchronized(lock) {
        validate()
        val merged = read(collection.id).entries.associateBy { it.identity }.toMutableMap()
        collection.entries.forEach { entry ->
            val old = merged[entry.identity]
            if (old == null || entry.datetime > old.datetime) merged[entry.identity] = entry
        }
        merged.values.groupBy { hash(it.path) }.forEach { (bucket, entries) ->
            val own = ownDirectory(collection.id)
            val file = own.resolve(bucket)
            val text = entries.sortedWith(compareBy({ it.path.joinToString("/") }, { it.key.toString() }))
                .joinToString("\n", postfix = "\n") { it.json().toString() }
            if (!Files.exists(file) || Files.readString(file) != text) atomicWrite(file, text)
        }
    }

    fun set(collection: String, path: List<String>, key: JsonElement, value: JsonElement): Entry = synchronized(lock) {
        val snapshot = read(collection)
        val previous = snapshot.entries.find { it.path == path && it.key == key }
        if (previous?.value == value) {
            // Re-announce on retry: an earlier crash may have written the bucket but not sequences.
            retain(snapshot)
            announce(collection, hash(path))
            return@synchronized previous
        }
        val now = LocalDateTime.now(ZoneOffset.UTC).withNano(0)
        val time = previous?.let { LocalDateTime.parse(it.datetime) }?.takeIf { it >= now }?.plusSeconds(1) ?: now
        val entry = Entry(path, time.format(TIMESTAMP), key, value)
        retain(Collection(collection, snapshot.entries.filter { it.identity != entry.identity } + entry))
        announce(collection, hash(path))
        entry
    }

    fun heartbeat(collection: String) {
        val snapshot = read(collection)
        val active = JsonPrimitive(java.time.LocalDate.now(ZoneOffset.UTC).toString())
        if (snapshot.info("last-active-$writer") != active) setInfo(collection, "last-active-$writer", active)
        if (snapshot.info("supported-version-$writer") != JsonPrimitive(2)) setInfo(collection, "supported-version-$writer", JsonPrimitive(2))
    }

    fun setInfo(collection: String, key: String, value: JsonElement) = set(collection, listOf("info"), JsonPrimitive(key), value)
    fun setResource(collection: String, uid: String, value: String?): Entry =
        set(collection, listOf("resources", uid), JsonNull, value?.let(::JsonPrimitive) ?: JsonNull)

    private fun announce(collection: String, bucket: String) {
        val file = ownDirectory(collection).resolve("sequences")
        val sequences = if (Files.exists(file)) Json.parseToJsonElement(Files.readString(file)).jsonObject.toMutableMap() else mutableMapOf()
        sequences[bucket] = JsonPrimitive((sequences[bucket]?.jsonPrimitive?.long ?: 0L) + 1L)
        atomicWrite(file, JsonObject(sequences).toString())
    }

    private fun collectionPath(collection: String): Path {
        require(collection.isNotBlank() && collection != "." && collection != ".." && !collection.contains('/') && !collection.contains('\\')) { "Invalid DecSync collection identifier." }
        val tasks = root.resolve("tasks")
        check(!Files.isSymbolicLink(tasks) && !Files.isSymbolicLink(tasks.resolve(collection))) { "DecSync directories must not be symbolic links." }
        return tasks.resolve(collection)
    }

    private fun ownDirectory(collection: String): Path {
        val v2 = collectionPath(collection).resolve("v2")
        val own = v2.resolve(writer)
        check(!Files.isSymbolicLink(v2) && !Files.isSymbolicLink(own)) { "DecSync directories must not be symbolic links." }
        Files.createDirectories(own)
        return own
    }

    private fun readFile(file: Path): List<Entry> {
        check(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Invalid DecSync entry file." }
        val text = Files.readString(file)
        // Treat partial writes as transient failures; no database changes or checkpoints follow.
        check(text.isEmpty() || text.endsWith('\n')) { "DecSync is receiving a file. Retry after synchronization finishes." }
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            try {
                val array = Json.parseToJsonElement(line).jsonArray
                require(array.size == 4)
                val path = array[0].jsonArray.map { it.jsonPrimitive.content }
                val datetime = array[1].jsonPrimitive.content
                LocalDateTime.parse(datetime)
                Entry(path, datetime, array[2], array[3])
            } catch (_: Exception) {
                error("DecSync contains an invalid entry. Wait for folder synchronization or restore the affected file.")
            }
        }.toList()
    }

    private fun atomicWrite(file: Path, text: String) {
        val temporary = Files.createTempFile(file.parent, ".tasks-", ".tmp")
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private val lock get() = locks.computeIfAbsent(root.toAbsolutePath().normalize()) { Any() }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<Path, Any>()
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        fun hash(path: List<String>): String {
            if (path == listOf("info")) return "info"
            return path.fold(0) { hash, part ->
                (199 * hash + part.toByteArray(Charsets.UTF_8).fold(0) { h, b -> (19 * h + (b.toInt() and 255)) and 255 }) and 255
            }.toString(16).padStart(2, '0')
        }
    }
}
