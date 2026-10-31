package com.macle.royaleassist.cache

import android.content.Context
import androidx.room3.Room

/**
 * The cache database in the app's databases folder (the same `lookup-cache.db` as before), on
 * Android's framework SQLite (Room's default driver on Android).
 */
fun CacheDb.Companion.create(context: Context): CacheDb =
    Room.databaseBuilder<CacheDb>(context, CacheDb.NAME, factory = { CacheDbConstructor.initialize() })
        .withCacheOptions()
        .build()
