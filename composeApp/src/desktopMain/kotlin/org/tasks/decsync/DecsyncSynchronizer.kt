package org.tasks.decsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tasks.broadcast.RefreshBroadcaster
import org.tasks.caldav.VtodoCache
import org.tasks.caldav.iCalendar
import org.tasks.caldav.iCalendar.Companion.fromVtodo
import org.tasks.data.UUIDHelper
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.DirtyDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.resources.getString
import org.tasks.service.TaskDeleter
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.subscription_required
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

class DecsyncSynchronizer(
    private val caldavDao: CaldavDao,
    private val dirtyDao: DirtyDao,
    private val iCal: iCalendar,
    private val cache: VtodoCache,
    private val taskDeleter: TaskDeleter,
    private val refreshBroadcaster: RefreshBroadcaster,
    private val stores: DecsyncStoreProvider,
) : DecsyncSyncer {
    override suspend fun sync(hasPro: Boolean) {
        caldavDao.getAccounts(CaldavAccount.TYPE_DECSYNC).forEach { sync(it, hasPro) }
    }

    suspend fun sync(account: CaldavAccount, hasPro: Boolean) = withContext(Dispatchers.IO) {
        try {
            check(hasPro) { getString(Res.string.subscription_required) }
            val store = stores.forAccount(account)
            // Missing directories never imply deletion. Only explicit DecSync tombstones do.
            for (collection in store.collections()) syncCollection(account, store, collection)
            account.lastSync = currentTimeMillis()
            account.error = ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // File paths and task data must not enter analytics or public diagnostic logs.
            account.error = if (!hasPro) getString(Res.string.subscription_required) else
                "Could not sync DecSync. Check the folder is available, fully synced, writable, and uses DecSync v2. Your pending changes are kept."
        }
        caldavDao.update(account)
        refreshBroadcaster.broadcastRefresh()
    }

    private suspend fun syncCollection(account: CaldavAccount, store: DecsyncStore, collection: DecsyncStore.Collection) {
        var calendar = caldavDao.getCalendarByUrl(account.uuid!!, collection.id)
        if (collection.deleted) {
            store.retain(collection)
            calendar?.let { taskDeleter.delete(it) }
            return
        }
        // Validate all resources before changing the database or publishing local work.
        val resources = collection.resources.associateWith { entry ->
            if (entry.value == kotlinx.serialization.json.JsonNull) null else {
                val text = (entry.value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("Invalid DecSync task")
                val todo = fromVtodo(text) ?: error("Invalid DecSync task")
                check(todo.uid == entry.path[1]) { "DecSync task identifier mismatch" }
                todo to text
            }
        }
        store.retain(collection)
        val color = collection.color?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
            ?.drop(1)?.toLong(16)?.toInt()?.or(0xff000000.toInt()) ?: 0
        if (calendar == null) {
            calendar = CaldavCalendar(account = account.uuid, url = collection.id, uuid = UUIDHelper.newUUID(), name = collection.name, color = color)
            caldavDao.insert(calendar)
        } else if (calendar.name != collection.name || calendar.color != color) {
            calendar = calendar.copy(name = collection.name, color = color)
            caldavDao.update(calendar)
        }
        for ((entry, resource) in resources) {
            val uid = entry.path[1]
            val existing = caldavDao.getTaskByRemoteId(calendar.uuid!!, uid)
            val etag = fingerprint(entry)
            if (resource == null) {
                // Keep an offline local edit; it will be republished rather than discarded.
                if (existing != null && dirtyDao.isDirty(existing.id) != true) {
                    if (existing.isDeleted()) caldavDao.delete(existing) else taskDeleter.delete(existing.task)
                }
            } else if (existing?.etag != etag) {
                val (todo, text) = resource
                iCal.fromVtodo(account, calendar, existing, todo, text, objectName(uid), etag)
            }
        }
        for (moved in caldavDao.getMoved(calendar.uuid!!)) {
            store.setResource(collection.id, requireNotNull(moved.remoteId), null)
            caldavDao.delete(moved)
        }
        for (pending in dirtyDao.getTasksToPush(calendar.uuid!!)) {
            val task = pending.task
            val remote = caldavDao.getCaldavTaskById(pending.caldavTaskId) ?: continue
            val uid = requireNotNull(remote.remoteId)
            if (task.isDeleted) {
                store.setResource(collection.id, uid, null)
                taskDeleter.delete(task)
            } else {
                val text = iCal.toVtodo(account, calendar, remote, task).decodeToString()
                val entry = store.setResource(collection.id, uid, text)
                cache.putVtodo(calendar, remote, text)
                remote.etag = fingerprint(entry)
                caldavDao.update(remote)
                dirtyDao.markPushed(remote.id, pending.dirtyVersion)
            }
        }
        store.heartbeat(collection.id)
        caldavDao.updateParents(calendar.uuid!!)
    }

    companion object {
        private fun objectName(uid: String) = "${digest(uid)}.ics"
        private fun fingerprint(entry: DecsyncStore.Entry) = digest(entry.json().toString())
        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
