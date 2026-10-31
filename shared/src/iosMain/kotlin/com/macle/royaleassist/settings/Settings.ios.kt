package com.macle.royaleassist.settings

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** `Library/Application Support/settings.preferences_pb` (DataStore creates the folder). */
fun settingsPath(): String {
    val dir = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    return "$dir/$SETTINGS_FILE"
}

/** [Settings] in the app's Application Support folder; create it once per process. */
fun Settings(saveScansDefault: Boolean, path: String = settingsPath()): Settings =
    Settings(createSettingsDataStore(path), saveScansDefault)
