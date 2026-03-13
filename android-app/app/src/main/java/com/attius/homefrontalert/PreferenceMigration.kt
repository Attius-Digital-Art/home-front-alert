package com.attius.homefrontalert

import android.content.Context
import android.util.Log

object PreferenceMigration {
    private const val NEW_PREFS = "HomeFrontAlertsPrefs"
    private const val LEGACY_GENERAL = "app_prefs"
    private const val LEGACY_LOCATION = "PikudAlertPrefs"
    private const val KEY_MIGRATION_COMPLETE = "migration_complete"

    fun migrateIfNeeded(context: Context) {
        val sharedPrefs = context.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE)
        if (sharedPrefs.contains(KEY_MIGRATION_COMPLETE)) return

        val legacyGeneral = context.getSharedPreferences(LEGACY_GENERAL, Context.MODE_PRIVATE)
        val legacyLocation = context.getSharedPreferences(LEGACY_LOCATION, Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()

        var migratedCount = 0

        // 1. Migrate General Settings
        if (legacyGeneral.contains("backend_url")) {
            editor.putString("backend_url", legacyGeneral.getString("backend_url", ""))
            migratedCount++
        }
        if (legacyGeneral.contains("hybrid_poller_on")) {
            editor.putBoolean("hybrid_poller_on", legacyGeneral.getBoolean("hybrid_poller_on", false))
            migratedCount++
        }
        if (legacyGeneral.contains("alert_volume")) {
            editor.putFloat("alert_volume", legacyGeneral.getFloat("alert_volume", 1.0f))
            migratedCount++
        }

        // 2. Migrate Location Settings
        if (legacyLocation.contains("USE_LIVE_GPS")) {
            editor.putBoolean("USE_LIVE_GPS", legacyLocation.getBoolean("USE_LIVE_GPS", true))
            migratedCount++
        }
        if (legacyLocation.contains("MANUAL_LAT")) {
            editor.putString("MANUAL_LAT", legacyLocation.getString("MANUAL_LAT", ""))
            migratedCount++
        }
        if (legacyLocation.contains("MANUAL_LNG")) {
            editor.putString("MANUAL_LNG", legacyLocation.getString("MANUAL_LNG", ""))
            migratedCount++
        }
        if (legacyLocation.contains("current_home_zone")) {
            editor.putString("current_home_zone", legacyLocation.getString("current_home_zone", ""))
            migratedCount++
        }

        editor.putBoolean(KEY_MIGRATION_COMPLETE, true)
        editor.apply()

        if (migratedCount > 0) {
            Log.d("HomeFrontAlerts", "Migrated $migratedCount settings from legacy preferences.")
        }
    }
}
