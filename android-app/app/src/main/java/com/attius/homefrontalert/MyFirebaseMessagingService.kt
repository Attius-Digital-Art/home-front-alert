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
        toneGenerator = DynamicToneGenerator(this)
        distanceCalculator = ZoneDistanceCalculator(this)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        if (!BuildConfig.IS_PAID) return

        Log.d("HomeFrontAlerts", "FCM Message received from: ${remoteMessage.from}")

        if (remoteMessage.data.isNotEmpty()) {
            val alertData = remoteMessage.data
            val citiesJsonStr = alertData["cities"]
            val alertType = alertData["type"] 
            val alertId = alertData["alertId"] ?: System.currentTimeMillis().toString()

            if (citiesJsonStr != null) {
                try {
                    val citiesArray = JSONArray(citiesJsonStr)
                    val cities = mutableListOf<String>()
                    for (i in 0 until citiesArray.length()) cities.add(citiesArray.getString(i))

                    val type = AlertStyleRegistry.getStyle("", alertType ?: "")
                    
                    // Log raw FCM metadata for diagnostics
                    StatusManager.logFcmDiagnostic(this, remoteMessage.data.toString())
                    
                    StatusManager.processAlert(this, alertId, type, cities, "[FCM]", toneGenerator, remoteMessage.data.toString())

                } catch (e: Exception) {
                    Log.e("HomeFrontAlerts", "FCM processing failed: ${e.message}")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d("HomeFrontAlerts", "Refreshed FCM token: $token")
    }
}
