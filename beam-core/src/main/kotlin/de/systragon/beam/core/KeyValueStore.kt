package de.systragon.beam.core

/**
 * Minimaler, plattformneutraler Schlüssel-Wert-Speicher. Erlaubt beam-core (android-frei) das
 * Persistieren von Einstellungen, ohne `android.content.SharedPreferences` zu kennen.
 *  - Android: Adapter über SharedPreferences (siehe SharedPrefsStore in :app).
 *  - Desktop: z. B. ein Properties-/Datei-basierter Adapter.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}
