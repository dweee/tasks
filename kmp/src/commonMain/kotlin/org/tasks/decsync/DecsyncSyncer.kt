package org.tasks.decsync

/** Optional platform adapter for syncing local DecSync folders. */
interface DecsyncSyncer {
    suspend fun sync(hasPro: Boolean)
}
