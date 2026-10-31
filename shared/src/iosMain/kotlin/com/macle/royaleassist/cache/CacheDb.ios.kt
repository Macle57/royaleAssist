package com.macle.royaleassist.cache

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** `Library/Application Support/lookup-cache.db` (the folder is created when missing). */
fun cacheDbPath(): String {
    val dir = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    return "$dir/${CacheDb.NAME}"
}

/** The cache database on iOS: bundled SQLite driver, queries on Dispatchers.IO. */
fun CacheDb.Companion.create(path: String = cacheDbPath()): CacheDb =
    Room.databaseBuilder<CacheDb>(path, factory = { CacheDbConstructor.initialize() })
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .withCacheOptions()
        .build()
