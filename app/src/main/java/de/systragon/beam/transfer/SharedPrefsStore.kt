package de.systragon.beam.transfer

import android.content.SharedPreferences
import de.systragon.beam.core.KeyValueStore

/** Android-Adapter: beam-core's KeyValueStore über SharedPreferences. */
class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
}
