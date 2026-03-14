package com.attius.homefrontalert

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private lateinit var toneGenerator: DynamicToneGenerator
    private lateinit var distanceCalculator: ZoneDistanceCalculator

    override fun onCreate() {
        super.onCreate()
        // Initialize the dependencies for audio and geographic logic
        toneGenerator = DynamicToneGenerator(this)
        distanceCalculator = ZoneDistanceCalculator(this)
    }

    /**
     * This method is triggered immediately when the Android device receives a high-priority push 
     * from our Node.js backend.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        if (!BuildConfig.IS_PAID) {
            Log.d("HomeFrontAlerts", "FCM Message ignored: Free version.")
            return
        }

        Log.d("HomeFrontAlerts", "FCM Message received from: ${remoteMessage.from}")

        // 1. Check if message contains a data payload (Invisible push notification)
        if (remoteMessage.data.isNotEmpty()) {
            val alertData = remoteMessage.data
            val citiesJsonStr = alertData["cities"]
            val alertType = alertData["type"] // e.g., "missiles", "terroristInfiltration"

            Log.d("HomeFrontAlerts", "Alert Type: $alertType, Cities: $citiesJsonStr")

            if (citiesJsonStr != null) {
                try {
                    // Parse the JSON array of alerted zones into a List<String>
                    val citiesArray = JSONArray(citiesJsonStr)
                    val alertedZones = mutableListOf<String>()
                    for (i in 0 until citiesArray.length()) {
                        alertedZones.add(citiesArray.getString(i))
                    }

                    // 2. Retrieve location using centralized logic
                    val locationManager = AppLocationManager(applicationContext)
                    val res = locationManager.resolveCurrentLocation()
                    val userLat = res.lat
                    val userLng = res.lng

                    // 3. Let our Cartography module calculate distances to every active polygon
                    val distances = distanceCalculator.calculateDistancesToAlerts(userLat, userLng, alertedZones)

                    Log.d("HomeFrontAlerts", "Calculated Distances KM: $distances")

                    val alertTypeEnum = AlertStyleRegistry.getStyle("", alertType ?: "")
                    val userNormalizedZone = StatusManager.normalizeCity(res.zoneNameHe)
                    val normalizedAlertedZones = alertedZones.map { StatusManager.normalizeCity(it) }
                    val isLocal = userNormalizedZone.isNotEmpty() && normalizedAlertedZones.contains(userNormalizedZone)

                    val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", android.content.Context.MODE_PRIVATE)
                    
                    // Update Active Threat Map instantly for SSOT
                    val threatsStr = sharedPrefs.getString("active_threat_map", "{}") ?: "{}"
                    val threats = org.json.JSONObject(threatsStr)
                    if (alertTypeEnum == AlertType.CALM) {
                        alertedZones.forEach { threats.remove(it) }
                    } else {
                        alertedZones.forEach { zone ->
                            val obj = org.json.JSONObject()
                            obj.put("t", System.currentTimeMillis())
                            obj.put("s", alertTypeEnum.name)
                            threats.put(zone, obj)
                        }
                    }
                    sharedPrefs.edit().putString("active_threat_map", threats.toString()).apply()

                    if (distances.isNotEmpty() || (alertTypeEnum == AlertType.CALM && isLocal)) {
                        val volume = sharedPrefs.getFloat("alert_volume", 1.0f)
                        toneGenerator.playTonesForDistances(distances, volume, alertTypeEnum, isLocal)

                        // Update Dashboard history
                        val closest = if (distances.isNotEmpty()) distances.min() else 0.0
                        sharedPrefs.edit()
                            .putString("last_alert_zones", alertedZones.joinToString(", "))
                            .putFloat("last_alert_dist", closest.toFloat())
                            .putLong("last_alert_time", System.currentTimeMillis())
                            .apply()
                    }
                    
                    // Sync dashboard status immediately
                    StatusManager.recalculateStatus(this)

                } catch (e: Exception) {
                    Log.e("HomeFrontAlerts", "Failed to parse cities payload", e)
                }
            }
        }
    }

    /**
     * Fired when the OS issues a new registration token to this device.
     */
    override fun onNewToken(token: String) {
        Log.d("HomeFrontAlerts", "Refreshed FCM token: $token")
        // Since we broadcast universally (via Topics), we don't strictly need to upload this
        // but it is useful for individual diagnostic testing.
    }
}
