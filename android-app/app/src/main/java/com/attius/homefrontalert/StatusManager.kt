package com.attius.homefrontalert

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Single Source of Truth for the application's alert status and current location.
 * Tracks active threats per-zone with a 30-minute stateful persistence.
 */
object StatusManager {
    private const val PREFS_NAME = "HomeFrontAlertsPrefs"

    fun updateStatus(context: Context, newStatus: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldStatus = prefs.getString("dash_status", "GREEN")
        
        if (newStatus == oldStatus) return

        prefs.edit().apply {
            putString("dash_status", newStatus)
            putLong("dash_status_start_ms", System.currentTimeMillis())
            apply()
        }
        
        syncUiComponents(context)
    }

    fun updateLocation(context: Context, zoneHe: String, lat: Double, lng: Double) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldZone = prefs.getString("current_home_zone", "")
        
        prefs.edit().apply {
            putString("current_home_zone", zoneHe)
            putString("last_known_lat", lat.toString())
            putString("last_known_lng", lng.toString())
            putString("last_known_zone_he", zoneHe)
            apply()
        }
        
        if (zoneHe != oldZone) {
            syncUiComponents(context)
        }
    }

    fun normalizeCity(city: String): String {
        return city.replace(Regex("[^\\u0590-\\u05FF0-9]"), "")
    }

    /**
     * Re-calculates status based on active threat map.
     * Threats expire after 30 minutes unless cleared explicitly.
     */
    fun recalculateStatus(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
        var threats = org.json.JSONObject(threatsStr)
        val homeZone = normalizeCity(prefs.getString("current_home_zone", "") ?: "")
        
        val now = System.currentTimeMillis()
        val threatTimeoutMs = 1800000L // 30 minutes
        
        val iter = threats.keys()
        while(iter.hasNext()) {
            val z = iter.next()
            val obj = threats.getJSONObject(z)
            // Remove threat if it's past the 30-min window
            if (now - obj.optLong("t", now) > threatTimeoutMs) { 
                iter.remove()
            }
        }
        
        // Persist cleaned map
        prefs.edit().putString("active_threat_map", threats.toString()).apply()
        
        var newStatus = "GREEN"
        if (threats.length() > 0) {
            // Priority 3: Any active alert in any zone -> YELLOW (Threat)
            newStatus = "YELLOW"
            
            val iter = threats.keys()
            while(iter.hasNext()) {
                val zone = iter.next()
                val obj = threats.getJSONObject(zone)
                
                if (normalizeCity(zone) == homeZone) {
                    val style = obj.optString("s", "URGENT")
                    if (style == "URGENT") {
                        // Priority 1: Urgent in home zone -> RED
                        newStatus = "RED"
                        break 
                    } else if (newStatus != "RED") {
                        // Priority 2: Caution in home zone -> ORANGE
                        newStatus = "ORANGE" 
                    }
                }
            }
        }

        updateStatus(context, newStatus)
    }

    fun syncUiComponents(context: Context) {
        StatusWidgetProvider.updateAllWidgets(context)
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("shield_active", false)) {
            val status = prefs.getString("dash_status", "GREEN") ?: "GREEN"
            val intent = android.content.Intent(context, LocalPollingService::class.java).apply {
                action = "UPDATE_NOTIFICATION"
                putExtra("status", status)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("HomeFrontAlerts", "UI Sync failed", e)
            }
        }
    }
}
