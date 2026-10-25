package com.lmj.labyrinthauto.net.bilibili

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemorySdkSessionStore : SdkSessionStore {
    private val sessions = mutableMapOf<String, SdkSession>()
    private val mutex = Mutex()

    override suspend fun save(key: String, session: SdkSession) {
        mutex.withLock { sessions[key] = session }
    }

    override suspend fun read(key: String): SdkSession? = mutex.withLock { sessions[key] }

    override suspend fun delete(key: String) {
        mutex.withLock { sessions.remove(key) }
    }
}
