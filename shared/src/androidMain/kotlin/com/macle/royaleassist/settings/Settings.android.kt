package com.macle.royaleassist.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File

private val stores = HashMap<String, DataStore<Preferences>>()

/** The settings store in `files/datastore/`, the file `preferencesDataStore("settings")` used before. */
fun settingsDataStore(context: Context): DataStore<Preferences> {
    val path = File(context.applicationContext.filesDir, "datastore/$SETTINGS_FILE").absolutePath
    return synchronized(stores) { stores.getOrPut(path) { createSettingsDataStore(path) } }
}

/** [Settings] backed by the app's settings store. */
fun Settings(context: Context, saveScansDefault: Boolean): Settings = Settings(settingsDataStore(context), saveScansDefault)
