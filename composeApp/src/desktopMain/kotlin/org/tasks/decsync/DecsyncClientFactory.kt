package org.tasks.decsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.tasks.caldav.CaldavClientFactory
import org.tasks.caldav.CaldavCollectionClient
import org.tasks.data.UUIDHelper
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar

class DecsyncClientFactory(private val delegate: CaldavClientFactory, private val stores: DecsyncStoreProvider) : CaldavClientFactory by delegate {
    override suspend fun forAccount(account: CaldavAccount, url: String?): CaldavCollectionClient =
        if (account.isDecsync) Client(stores.forAccount(account), url?.takeIf { it != account.url })
        else delegate.forAccount(account, url)

    private class Client(private val store: DecsyncStore, private val collection: String?) : CaldavCollectionClient {
        override fun close() {}
        override suspend fun makeCollection(displayName: String, color: Int, icon: String?): String = withContext(Dispatchers.IO) {
            val id = UUIDHelper.newUUID()
            update(id, displayName, color)
            id
        }
        override suspend fun updateCollection(displayName: String, color: Int, icon: String?) = withContext(Dispatchers.IO) {
            update(requireNotNull(collection), displayName, color)
        }
        private fun update(id: String, name: String, color: Int) {
            store.setInfo(id, "name", JsonPrimitive(name))
            store.setInfo(id, "color", JsonPrimitive("#%06x".format(color and 0xffffff)))
        }
        override suspend fun deleteCollection() { withContext(Dispatchers.IO) { store.setInfo(requireNotNull(collection), "deleted", JsonPrimitive(true)) } }
        override suspend fun share(account: CaldavAccount, href: String): Unit = error("Share the DecSync folder using Syncthing.")
        override suspend fun removePrincipal(account: CaldavAccount, calendar: CaldavCalendar, href: String): Unit = error("Manage folder sharing in Syncthing.")
    }
}
