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
            putLong("last_location_update_ms", System.currentTimeMillis())
            apply()
        }
        
        syncUiComponents(context)
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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("HomeFrontAlerts", "UI Sync failed", e)
            }
        }
    }

    /**
     * Unified Polling Engine: Can be called by Service or Activity.
     * Processes alerts, updates history, and maintains the baseline.
     */
    fun runPollCycle(context: android.content.Context, forceBackend: Boolean = false) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val proxyRoot = BuildConfig.BACKEND_URL
        val proxyUrl = if (proxyRoot.endsWith("/alerts")) proxyRoot else "$proxyRoot/alerts"

        var hfcStatus = "Pending"
        var success = false

        // 1. Try Direct HFC (Unless forced backend)
        if (!forceBackend) {
            try {
                val hfcUrl = java.net.URL("https://www.oref.org.il/WarningMessages/alert/Alerts.json")
                val conn = hfcUrl.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "PikudHaoref/1.6 (iPhone; iOS 17.4; Scale/3.00)")
                conn.setRequestProperty("Referer", "https://www.oref.org.il/")
                
                val code = conn.responseCode
                hfcStatus = "HFC: $code"

                if (code == 200 || code == 204) {
                    val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                    success = handlePollResult(context, body, "[HFC]", hfcStatus)
                }
            } catch (e: Exception) { hfcStatus = "HFC: Fail" }
        }

        // 2. Try Backend Proxy
        if (!success && BuildConfig.IS_PAID) {
            try {
                val url = java.net.URL(proxyUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("X-API-Key", BuildConfig.API_KEY)
                
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    success = handlePollResult(context, body, "[Backend]", "OK")
                } else {
                    hfcStatus += " | Backend: ${conn.responseCode}"
                }
            } catch (e: Exception) { hfcStatus += " | Backend: Fail" }
        }

        if (!success && !forceBackend) {
            sharedPrefs.edit().putString("shield_last_log", "[$now] Shield Offline | $hfcStatus").apply()
        }
    }

    private fun handlePollResult(context: android.content.Context, body: String, sourceTag: String, statusInfo: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val nowTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        
        val clean = body.trim()
        var isEffectivelyEmpty = clean.isEmpty() || clean == "null" || clean == "[]" || clean == "{}"
        
        // Deep check for Backend structured format: {"active": {}, ...}
        if (!isEffectivelyEmpty && clean.startsWith("{")) {
            try {
                val jsonObj = org.json.JSONObject(clean)
                val active = if (jsonObj.has("active")) jsonObj.get("active") else null
                
                // baseline if missing 'active' or if 'active' is an empty object/array
                val isEmptyActive = active == null || 
                                    (active is org.json.JSONObject && active.length() == 0) || 
                                    (active is org.json.JSONArray && active.length() == 0) ||
                                    active.toString() == "{}" || active.toString() == "[]"
                
                if (isEmptyActive) {
                    isEffectivelyEmpty = true
                }
            } catch (e: Exception) {}
        }
        
        if (isEffectivelyEmpty) {
            val baselineKey = if (sourceTag.contains("HFC")) "empty_sample_hfc" else "empty_sample_backend"
            prefs.edit().putString("shield_last_log", "[$nowTime] $sourceTag OK (Baseline)").apply()
            val baselineRep = if (clean.isEmpty()) "[EMPTY]" else if (clean.length > 50) clean.take(47) + "..." else clean
            prefs.edit().putString(baselineKey, baselineRep).apply()
            prefs.edit().putLong("shield_last_success_ms", System.currentTimeMillis()).apply()
            return true
        }

        // --- Data Found ---
        prefs.edit().putString("shield_last_log", "[$nowTime] $sourceTag DATA!").apply()
        prefs.edit().putLong("shield_last_success_ms", System.currentTimeMillis()).apply()
        
        // Update history
        val history = prefs.getString("raw_alert_history", "") ?: ""
        val entry = "[$nowTime] $sourceTag: ${clean.take(150).replace("\n", " ")}"
        if (!history.contains(clean.take(30))) {
            val lines = history.split("\n").filter { it.isNotEmpty() }.toMutableList()
            lines.add(0, entry)
            prefs.edit().putString("raw_alert_history", lines.take(5).joinToString("\n")).apply()
        }

        // Alert Processing (Update Threat Map instantly)
        try {
            val root = org.json.JSONObject(body)
            val jsonObject = if (root.has("active") && !root.isNull("active")) {
                val active = root.get("active")
                if (active is org.json.JSONObject) active 
                else if (active is org.json.JSONArray && active.length() > 0) active.getJSONObject(0) 
                else null
            } else if (root.has("cat")) root else null

            if (jsonObject != null) {
                val cat = jsonObject.optString("cat", "")
                val title = jsonObject.optString("title", "")
                val type = AlertStyleRegistry.getStyle(cat, title)
                val cities = jsonObject.optJSONArray("cities") ?: jsonObject.optJSONArray("data")
                
                if (cities != null && cities.length() > 0) {
                    val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
                    val threats = org.json.JSONObject(threatsStr)
                    
                    for (i in 0 until cities.length()) {
                        val zone = cities.getString(i)
                        if (type == AlertType.CALM) {
                            threats.remove(zone)
                        } else {
                            val obj = org.json.JSONObject()
                            obj.put("t", System.currentTimeMillis())
                            obj.put("s", type.name)
                            threats.put(zone, obj)
                        }
                    }
                    prefs.edit().putString("active_threat_map", threats.toString()).apply()
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Alert processing error: ${e.message}")
        }
        
        return true
    }
}
