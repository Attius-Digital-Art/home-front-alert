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

                    // 2. Retrieve the user's GPS Location or Manual Configured UI Setting
                    val locationManager = AppLocationManager(applicationContext)
                    var currentLocation = locationManager.getCurrentLocationSync()
                    
                    if (currentLocation == null) {
                        val lat = locationManager.getManualLat()?.toDoubleOrNull()
                        val lng = locationManager.getManualLng()?.toDoubleOrNull()
                        if (lat != null && lng != null) {
                            currentLocation = Pair(lat, lng)
                            Log.i("HomeFrontAlerts", "FCM location fallback to manual.")
                        }
                    }

                    if (currentLocation != null) {
                        val userLat = currentLocation.first
                        val userLng = currentLocation.second

                        // 3. Let our Cartography module calculate distances to every active polygon
                        val distances = distanceCalculator.calculateDistancesToAlerts(userLat, userLng, alertedZones)

                        Log.d("HomeFrontAlerts", "Calculated Distances KM: $distances")

                        // 4. Feed the distances directly into the Hardware Synthesizer 
                        if (distances.isNotEmpty()) {
                            val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", android.content.Context.MODE_PRIVATE)
                            val volume = sharedPrefs.getFloat("alert_volume", 1.0f)
                            toneGenerator.playTonesForDistances(distances, volume)

                            // Update Dashboard history for consistent UI
                            val closest = distances.minOrNull() ?: 0.0
                            sharedPrefs.edit()
                                .putString("last_alert_zones", alertedZones.joinToString(", "))
                                .putFloat("last_alert_dist", closest.toFloat())
                                .putLong("last_alert_time", System.currentTimeMillis())
                                .apply()
                        } else {
                            Log.d("HomeFrontAlerts", "Distances array empty. Zones were not found in cache.")
                        }
                    } else {
                        Log.e("HomeFrontAlerts", "Location is null, cannot calculate distances.")
                    }

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
