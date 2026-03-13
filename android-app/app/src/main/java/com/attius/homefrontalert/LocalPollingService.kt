package com.attius.homefrontalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * A highly-optimized, localized connectivity shield that bypasses Firebase 
 * to provided calculated sub-second latency for critical life-safety alerts.
 */
class LocalPollingService : Service() {

    private var pollingThread: Thread? = null
    private var isRunning = false

    private lateinit var distanceCalculator: ZoneDistanceCalculator
    private lateinit var toneGenerator: DynamicToneGenerator
    private val recentlyPlayedAlerts = mutableSetOf<String>()

    companion object {
        const val NOTIFICATION_ID = 991
        const val CHANNEL_ID = "LocalShieldChannel"
    }

    override fun onCreate() {
        super.onCreate()
        distanceCalculator = ZoneDistanceCalculator(this)
        toneGenerator = DynamicToneGenerator(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createStatusNotification("Direct Shield Active", "GREEN"))
        
        isRunning = true
        pollingThread = kotlin.concurrent.thread(start = true) {
            while (isRunning) {
                try {
                    pollCycle()
                } catch (e: Exception) {
                    Log.e("HomeFrontAlerts", "Poll cycle error", e)
                }
                Thread.sleep(1500)
            }
        }
        
        Log.i("HomeFrontAlerts", "Connectivity Shield Service Started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        pollingThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Direct Connectivity Shield",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun pollCycle() {
        val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val proxyRoot = BuildConfig.BACKEND_URL
        val proxyUrl = if (proxyRoot.endsWith("/alerts")) proxyRoot else "$proxyRoot/alerts"

        var hfcStatus: String
        var success = false

        // 1. PRIMARY: Try HFC Direct
        try {
            val hfcUrl = URL("https://www.oref.org.il/WarningMessages/alert/Alerts.json")
            val conn = hfcUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "PikudHaoref/1.6 (iPhone; iOS 17.4; Scale/3.00)")
            conn.setRequestProperty("Referer", "https://www.oref.org.il/")
            
            val code = conn.responseCode
            hfcStatus = "HFC: $code"

            if (code == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                saveRawAlertToLog("[HFC]", body)
                
                val clean = body.trim()
                val isData = clean.isNotEmpty() && clean != "null" && clean != "{}" && clean != "[]"
                
                if (isData) {
                    processPolledAlertData(body, "[HFC]")
                    sharedPrefs.edit().putString("shield_last_log", "[$now] [HFC] DATA! ... $hfcStatus").apply()
                    sharedPrefs.edit().putLong("shield_last_success_ms", System.currentTimeMillis()).apply()
                    success = true
                } else {
                    sharedPrefs.edit().putString("shield_last_log", "[$now] Direct HFC OK (No Data)").apply()
                    sharedPrefs.edit().putLong("shield_last_success_ms", System.currentTimeMillis()).apply()
                    success = true
                }
            } else if (code == 204) {
                sharedPrefs.edit().putString("shield_last_log", "[$now] Direct HFC OK (204)").apply()
                sharedPrefs.edit().putLong("shield_last_success_ms", System.currentTimeMillis()).apply()
                success = true
            }
        } catch (e: Exception) {
            hfcStatus = "HFC: Fail"
        }

        // 2. SECONDARY: If HFC failed or blocked, try Global Proxy (PAID ONLY)
        if (!success && BuildConfig.IS_PAID) {
            try {
                val url = URL(proxyUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "HomeFrontAlerts-Android/1.3.5")
                conn.setRequestProperty("X-API-Key", BuildConfig.API_KEY)
                
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    saveRawAlertToLog("[Backend]", body)
                    if (body.trim().isNotEmpty() && body != "null" && body != "{}" && body != "[]") {
                        processPolledAlertData(body, "[Backend]")
                        sharedPrefs.edit().putString("shield_last_log", "[$now] [Backend] DATA! | $hfcStatus").apply()
                    } else {
                        sharedPrefs.edit().putString("shield_last_log", "[$now] [Backend] OK (204) | $hfcStatus").apply()
                    }
                    sharedPrefs.edit().putLong("shield_last_success_ms", System.currentTimeMillis()).apply()
                    success = true
                } else {
                    sharedPrefs.edit().putString("shield_last_log", "[$now] [Backend] Err $code | $hfcStatus").apply()
                }
            } catch (e: Exception) {
                sharedPrefs.edit().putString("shield_last_log", "[$now] [Backend] Fail | $hfcStatus").apply()
            }
        }
        
        if (!success) {
            sharedPrefs.edit().putString("shield_last_log", "[$now] Shield Offline | $hfcStatus").apply()
        }
    }

    private fun saveRawAlertToLog(source: String, body: String) {
        val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        
        val cleanBody = body.trim()
        val isCommonEmpty = cleanBody.isEmpty() || cleanBody == "{}" || cleanBody == "[]" || cleanBody == "null"

        // Update the baseline display. Even if it's 'null', it IS our baseline.
        if (isCommonEmpty) {
            sharedPrefs.edit().putString("empty_sample_log", if (cleanBody.isEmpty()) "[EMPTY STRING]" else cleanBody).apply()
            return
        }

        val history = sharedPrefs.getString("raw_alert_history", "") ?: ""
        val entry = "[$now] $source: ${cleanBody.take(200).replace("\n", " ")}"
        
        if (history.contains(cleanBody.take(50))) return

        val lines: MutableList<String> = history.split("\n").filter { it.isNotEmpty() }.toMutableList()
        lines.add(0, entry)
        sharedPrefs.edit().putString("raw_alert_history", lines.take(5).joinToString("\n")).apply()
    }

    private fun normalizeCity(city: String): String {
        return city.replace(Regex("[^\\u0590-\\u05FF0-9]"), "")
    }

    private fun processPolledAlertData(jsonStr: String, prefixTag: String) {
        try {
            val root = JSONObject(jsonStr)
            val jsonObject = if (root.has("active") && !root.isNull("active")) {
                root.getJSONObject("active")
            } else if (root.has("cat")) {
                root
            } else {
                return 
            }
            
            val cat = jsonObject.optString("cat", "")
            val alertId = jsonObject.optString("id", System.currentTimeMillis().toString())
            val citiesArray = jsonObject.optJSONArray("cities") ?: jsonObject.optJSONArray("data")
            
            if (citiesArray != null && citiesArray.length() > 0 && !recentlyPlayedAlerts.contains(alertId)) {
                val alertedZones = mutableListOf<String>()
                val normalizedAlertedZones = mutableSetOf<String>()
                for (i in 0 until citiesArray.length()) {
                    val z = citiesArray.getString(i)
                    alertedZones.add(z)
                    normalizedAlertedZones.add(normalizeCity(z))
                }

                val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
                val threatsStr = sharedPrefs.getString("active_threat_map", "{}") ?: "{}"
                val threats = JSONObject(threatsStr)
                val alertStyle = AlertStyleRegistry.getStyle(cat, jsonObject.optString("title", ""))

                if (alertStyle == AlertType.CALM) {
                    alertedZones.forEach { threats.remove(it) }
                } else {
                    alertedZones.forEach { zone ->
                        val obj = JSONObject()
                        obj.put("t", System.currentTimeMillis())
                        obj.put("s", alertStyle.name)
                        threats.put(zone, obj)
                    }
                }
                sharedPrefs.edit().putString("active_threat_map", threats.toString()).apply()

                val locationManager = AppLocationManager(applicationContext)
                val finalLocation = locationManager.getCurrentLocationSync()
                val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(finalLocation.first, finalLocation.second)
                
                val currentHomeZone = if (zoneInfo != null && zoneInfo.second < 150.0) zoneInfo.first else "Out of Range"
                val userNormalizedZone = normalizeCity(currentHomeZone)
                
                sharedPrefs.edit()
                    .putString("current_home_zone", currentHomeZone)
                    .putString("last_known_lat", finalLocation.first.toString())
                    .putString("last_known_lng", finalLocation.second.toString())
                    .apply()

                if (alertStyle != AlertType.CALM) {
                    val distances = distanceCalculator.calculateDistancesToAlerts(finalLocation.first, finalLocation.second, alertedZones)
                    val closest = if (distances.isNotEmpty()) distances.min() else -1.0
                    
                    sharedPrefs.edit()
                        .putString("last_alert_zones", alertedZones.joinToString(", "))
                        .putFloat("last_alert_dist", closest.toFloat())
                        .putLong("last_alert_time", System.currentTimeMillis())
                        .apply()

                    val volume = sharedPrefs.getFloat("alert_volume", 1.0f)
                    recentlyPlayedAlerts.add(alertId)
                    if (recentlyPlayedAlerts.size > 100) recentlyPlayedAlerts.remove(recentlyPlayedAlerts.first())
                    toneGenerator.playTonesForDistances(distances, volume, alertStyle)
                } else {
                    if (userNormalizedZone.isNotEmpty() && normalizedAlertedZones.contains(userNormalizedZone)) {
                        toneGenerator.playTonesForDistances(emptyList(), sharedPrefs.getFloat("alert_volume", 1.0f), AlertType.CALM)
                    }
                }
                updateDashboardStatus()
            }
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Shield Parse Error: ${e.message}")
        }
    }

    private fun updateDashboardStatus() {
        val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        val threatsStr = sharedPrefs.getString("active_threat_map", "{}") ?: "{}"
        val threats = JSONObject(threatsStr)
        val homeZone = normalizeCity(sharedPrefs.getString("current_home_zone", "") ?: "")
        
        var newStatus = "GREEN"
        if (threats.length() > 0) {
            newStatus = "YELLOW"
            val iter = threats.keys()
            while(iter.hasNext()) {
                val zone = iter.next()
                val obj = threats.getJSONObject(zone)
                if (normalizeCity(zone) == homeZone) {
                    newStatus = if (obj.optString("s") == "URGENT") "RED" else "ORANGE"
                    if (newStatus == "RED") break
                }
            }
        }

        val oldStatus = sharedPrefs.getString("dash_status", "GREEN")
        if (newStatus != oldStatus) {
            sharedPrefs.edit().putString("dash_status", newStatus).putLong("dash_status_start_ms", System.currentTimeMillis()).apply()
            updateForegroundNotification(newStatus)
        }
        StatusWidgetProvider.updateAllWidgets(this)
    }

    private fun updateForegroundNotification(status: String) {
        val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        val zone = sharedPrefs.getString("current_home_zone", "Jerusalem")
        val vol = (sharedPrefs.getFloat("alert_volume", 1.0f) * 100).toInt()
        
        val statusText = when(status) {
            "RED" -> getString(R.string.notif_critical)
            "ORANGE" -> getString(R.string.notif_warning)
            "YELLOW" -> getString(R.string.notif_threat)
            else -> getString(R.string.notif_ok)
        }
        
        val content = "Zone: $zone | Vol: $vol%\n$statusText"
        val notification = createStatusNotification(content, status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createStatusNotification(content: String, status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val color = when(status) {
            "RED" -> Color.RED
            "ORANGE" -> Color.parseColor("#FF9500")
            "YELLOW" -> Color.YELLOW
            else -> Color.GREEN
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(color)
            .setColorized(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
