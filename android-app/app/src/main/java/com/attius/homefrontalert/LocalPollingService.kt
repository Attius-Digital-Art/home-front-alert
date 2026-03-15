package com.attius.homefrontalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private lateinit var locationManager: AppLocationManager
    private val recentlyPlayedAlerts = mutableSetOf<String>()

    companion object {
        const val NOTIFICATION_ID = 991
        const val CHANNEL_ID = "LocalShieldChannel"
    }

    override fun onCreate() {
        super.onCreate()
        distanceCalculator = ZoneDistanceCalculator(this)
        locationManager = AppLocationManager.getInstance(this)
        locationManager.startTracking()
        toneGenerator = DynamicToneGenerator(this)
        createNotificationChannel()
        val notification = createStatusNotification("Direct Shield Active", "GREEN")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        isRunning = true
        getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE).edit().putBoolean("shield_active", true).apply()
        pollingThread = kotlin.concurrent.thread(start = true) {
            while (isRunning) {
                try {
                    pollCycle()
                } catch (e: Exception) {
                    Log.e("HomeFrontAlerts", "Poll cycle error", e)
                }
                try {
                    Thread.sleep(1500)
                } catch (e: InterruptedException) {
                    isRunning = false
                    break
                }
            }
        }
        
        Log.i("HomeFrontAlerts", "Connectivity Shield Service Started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_NOTIFICATION") {
            val status = intent.getStringExtra("status") ?: "GREEN"
            updateForegroundNotification(status)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE).edit().putBoolean("shield_active", false).apply()
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
        StatusManager.runPollCycle(this, toneGenerator = toneGenerator)
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
            val alertStyle = AlertStyleRegistry.getStyle(cat, jsonObject.optString("title", ""))
            
            var processedId = jsonObject.optString("id", System.currentTimeMillis().toString())
            if (alertStyle == AlertType.CALM) {
                processedId += "_clear"
            }
            val alertId = processedId
            
            val citiesArray = jsonObject.optJSONArray("cities") ?: jsonObject.optJSONArray("data")
            val citiesCount = citiesArray?.length() ?: 0
            
            Log.d("HomeFrontAlerts", "[Shield] Processing Alert ID: $alertId | Style: $alertStyle | Cities: $citiesCount")

            if (citiesArray != null && citiesArray.length() > 0) {
                if (recentlyPlayedAlerts.contains(alertId)) {
                    Log.d("HomeFrontAlerts", "[Shield] Alert ID $alertId already in cache. Skipping audio/record.")
                } else {
                    val alertedZones = mutableListOf<String>()
                    val normalizedAlertedZones = mutableSetOf<String>()
                    for (i in 0 until citiesArray.length()) {
                        val z = citiesArray.getString(i)
                        alertedZones.add(z)
                        normalizedAlertedZones.add(StatusManager.normalizeCity(z))
                    }

                    val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
                    val threatsStr = sharedPrefs.getString("active_threat_map", "{}") ?: "{}"
                    val threats = JSONObject(threatsStr)

                    if (alertStyle == AlertType.CALM) {
                        alertedZones.forEach { threats.remove(it) }
                    } else {
                        alertedZones.forEach { zone ->
                            val obj = JSONObject()
                            obj.put("t", System.currentTimeMillis())
                            obj.put("s", alertStyle.name)
                            obj.put("c", distanceCalculator.getZoneCountdown(zone))
                            threats.put(zone, obj)
                        }
                    }
                    sharedPrefs.edit().putString("active_threat_map", threats.toString()).apply()

                    val locationManager = AppLocationManager(applicationContext)
                    val res = locationManager.resolveCurrentLocation() 
                    val finalLocation = Pair(res.lat, res.lng)
                    val currentHomeZone = res.zoneNameHe
                    val userNormalizedZone = StatusManager.normalizeCity(currentHomeZone)
                    
                    StatusManager.updateLocation(this, currentHomeZone, res.lat, res.lng)

                    if (alertStyle != AlertType.CALM) {
                        val distances = distanceCalculator.calculateDistancesToAlerts(finalLocation.first, finalLocation.second, alertedZones)
                        val closest = if (distances.isNotEmpty()) distances.min() else -1.0
                        
                        Log.d("HomeFrontAlerts", "[Shield] Style: $alertStyle | Count: ${alertedZones.size} | MinDist: $closest km")

                        sharedPrefs.edit()
                            .putString("last_alert_zones", alertedZones.joinToString(", "))
                            .putFloat("last_alert_dist", closest.toFloat())
                            .putLong("last_alert_time", System.currentTimeMillis())
                            .apply()

                        val volume = sharedPrefs.getFloat("alert_volume", 1.0f)
                        recentlyPlayedAlerts.add(processedId)
                        if (recentlyPlayedAlerts.size > 100) recentlyPlayedAlerts.remove(recentlyPlayedAlerts.first())
                        val isLocal = normalizedAlertedZones.contains(userNormalizedZone)
                        
                        if (distances.isNotEmpty()) {
                            toneGenerator.playTonesForDistances(distances, volume, alertStyle, isLocal)
                        } else if (isLocal) {
                            // If cartography fails but we know it's local by name, still play local urgency
                            toneGenerator.playTonesForDistances(listOf(0.0), volume, alertStyle, true)
                        } else {
                            Log.d("HomeFrontAlerts", "[Shield] No distances found. Sound skipped.")
                        }
                    } else {
                        val isLocal = normalizedAlertedZones.contains(userNormalizedZone)
                        recentlyPlayedAlerts.add(processedId) 
                        if (recentlyPlayedAlerts.size > 100) recentlyPlayedAlerts.remove(recentlyPlayedAlerts.first())
                        if (userNormalizedZone.isNotEmpty() && isLocal) {
                            toneGenerator.playTonesForDistances(emptyList(), sharedPrefs.getFloat("alert_volume", 1.0f), AlertType.CALM, true)
                        }
                    }
                }
            }
            // Move status update outside the ID check so repeat polls still ensure consistency
            StatusManager.recalculateStatus(this)
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Shield Parse Error: ${e.message}")
        }
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
