package com.attius.homefrontalert

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks

enum class LocationTrackingMode {
    GPS_LIVE,
    FIXED_ZONE
}

data class ResolvedLocation(
    val lat: Double,
    val lng: Double,
    val zoneNameHe: String,
    val isFallback: Boolean,
    val source: String,      // "GPS", "SAVED", "DEFAULT"
    val activeMode: LocationTrackingMode,
    val provider: String = "Unknown",
    val accuracy: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis(),
    val isMock: Boolean = false
)

class AppLocationManager(private val context: Context) {

    private val sharedPreferences = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val distanceCalculator = ZoneDistanceCalculator(context)

    // The Israel Museum, Jerusalem (Hardcoded Default)
    companion object {
        const val DEFAULT_LAT = 31.7719
        const val DEFAULT_LNG = 35.2017
        const val DEFAULT_ZONE_HE = "מוזיאון ישראל, ירושלים"
        
        @Volatile
        private var INSTANCE: AppLocationManager? = null

        fun getInstance(context: Context): AppLocationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppLocationManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Static memory cache shared across all instances (SSOT)
        @Volatile
        private var sharedCachedLocation: ResolvedLocation? = null
        
        // Deep Telemetry
        @Volatile var satellitesInView = 0
        @Volatile var satellitesUsed = 0
        @Volatile var avgSnr = 0f
    }

    private var isTracking = false

    private val gnssStatusListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val count = status.satelliteCount
                var used = 0
                var snrSum = 0f
                for (i in 0 until count) {
                    if (status.usedInFix(i)) used++
                    snrSum += status.getCn0DbHz(i)
                }
                satellitesInView = count
                satellitesUsed = used
                avgSnr = if (count > 0) snrSum / count else 0f
            }
        }
    } else null

    private val locationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d("HomeFrontAlerts", "SENSOR LOCK: ${location.provider} @ (${location.latitude}, ${location.longitude}) Accuracy: ${location.accuracy}m")
            val mode = getTrackingMode()
            if (mode == LocationTrackingMode.FIXED_ZONE) return

            val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(location.latitude, location.longitude)
            if (zoneInfo != null) {
                val res = ResolvedLocation(
                    location.latitude, 
                    location.longitude, 
                    zoneInfo.first, 
                    false, 
                    "GPS", 
                    mode, 
                    location.provider ?: "sensor", 
                    location.accuracy,
                    System.currentTimeMillis(),
                    location.isFromMockProvider
                )
                updateSessionCache(res)
                savePersistentLocation(res.lat, res.lng, res.zoneNameHe)
            }
        }
        override fun onStatusChanged(p0: String?, p1: Int, p2: android.os.Bundle?) {}
        override fun onProviderEnabled(p0: String) {}
        override fun onProviderDisabled(p0: String) {}
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val looper = Looper.getMainLooper()
            if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, looper)
            }
            if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener, looper)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusListener != null) {
                lm.registerGnssStatusCallback(gnssStatusListener, Handler(Looper.getMainLooper()))
            }
            isTracking = true
            Log.i("HomeFrontAlerts", "Location tracking started")
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Failed to start tracking", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.removeUpdates(locationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusListener != null) {
                lm.unregisterGnssStatusCallback(gnssStatusListener)
            }
            isTracking = false
            Log.i("HomeFrontAlerts", "Location tracking stopped")
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Failed to stop tracking", e)
        }
    }

    fun getTrackingMode(): LocationTrackingMode {
        val modeStr = sharedPreferences.getString("tracking_mode", LocationTrackingMode.GPS_LIVE.name)
        return try { LocationTrackingMode.valueOf(modeStr!!) } catch (e: Exception) { LocationTrackingMode.GPS_LIVE }
    }

    fun setTrackingMode(mode: LocationTrackingMode) {
        sharedPreferences.edit().putString("tracking_mode", mode.name).apply()
        // Clear session cache when switching modes to prevent SSOT violations (e.g. old GPS showing in Fixed mode)
        sharedCachedLocation = null
    }

    /**
     * Legacy support for the boolean switch used in older versions
     */
    fun isUsingLiveGps(): Boolean {
        return getTrackingMode() == LocationTrackingMode.GPS_LIVE
    }

    fun setUsingLiveGps(use: Boolean) {
        setTrackingMode(if (use) LocationTrackingMode.GPS_LIVE else LocationTrackingMode.FIXED_ZONE)
    }

    fun getSavedZoneHe(): String? {
        return sharedPreferences.getString("fixed_zone_he", null)
    }

    fun setSavedZoneHe(zoneHe: String?) {
        val editor = sharedPreferences.edit()
        editor.putString("fixed_zone_he", zoneHe)
        
        zoneHe?.let {
            val loc = distanceCalculator.getZoneCoordinates(it)
            if (loc != null) {
                editor.putString("manual_lat", loc.lat.toString())
                editor.putString("manual_lng", loc.lng.toString())
                savePersistentLocation(loc.lat, loc.lng, it)
            }
        }
        editor.apply()
    }

    private fun getSessionCache(): ResolvedLocation? {
        val cache = sharedCachedLocation
        if (cache != null && (System.currentTimeMillis() - cache.timestampMs) < 120000) { // 2 min TTL
            return cache
        }
        return null
    }

    private fun updateSessionCache(loc: ResolvedLocation) {
        sharedCachedLocation = loc
    }

    /**
     * Resolves the location based on current settings and environmental availability.
     */
    @SuppressLint("MissingPermission")
    fun resolveCurrentLocation(): ResolvedLocation {
        val mode = getTrackingMode()
        val savedZoneHe = getSavedZoneHe()
        
        // 1. FIXED MODE: Immediate exit
        if (mode == LocationTrackingMode.FIXED_ZONE) {
            if (savedZoneHe != null) {
                val loc = distanceCalculator.getZoneCoordinates(savedZoneHe)
                if (loc != null) {
                    return ResolvedLocation(loc.lat, loc.lng, savedZoneHe, false, "SAVED", mode, "Manual")
                }
            }
            return ResolvedLocation(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZONE_HE, true, "DEFAULT", mode, "Hard_Default")
        }

        // 2. LIVE GPS MODE: Check Active Listener Data first
        try {
            // A. Recent Session Cache from Listener (Fastest)
            getSessionCache()?.let { return it.copy(activeMode = mode) }

            // B. Short-wait for any system fix if cache empty
            Log.d("HomeFrontAlerts", "Cache empty, trying one-shot Fused...")
            val freshTask = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val freshLoc: Location? = Tasks.await(freshTask, 3, java.util.concurrent.TimeUnit.SECONDS)
            if (freshLoc != null) {
                val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(freshLoc.latitude, freshLoc.longitude)
                if (zoneInfo != null) {
                    val res = ResolvedLocation(freshLoc.latitude, freshLoc.longitude, zoneInfo.first, false, "GPS", mode, freshLoc.provider ?: "fused", freshLoc.accuracy)
                    updateSessionCache(res)
                    savePersistentLocation(res.lat, res.lng, res.zoneNameHe)
                    return res
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "GPS FastResolve failed", e)
        }

        // 3. FAILBACKS (In GPS mode but searching failed)
        
        // 3a. Persistent Persistence (Last known successfully verified location - Retained indefinitely per user request)
        val pLat = sharedPreferences.getString("last_known_lat", null)?.toDoubleOrNull()
        val pLng = sharedPreferences.getString("last_known_lng", null)?.toDoubleOrNull()
        val pZone = sharedPreferences.getString("last_known_zone_he", null)
        
        if (pLat != null && pLng != null && pZone != null) {
            Log.d("HomeFrontAlerts", "GPS Fail: Using persistent fallback: $pZone")
            return ResolvedLocation(pLat, pLng, pZone, true, "SAVED", mode, "Stored_GPS", -1.0f)
        }

        // 3b. Manual Zone Fallback
        if (savedZoneHe != null) {
            val loc = distanceCalculator.getZoneCoordinates(savedZoneHe)
            if (loc != null) {
                return ResolvedLocation(loc.lat, loc.lng, savedZoneHe, true, "SAVED", mode, "Manual_Fallback", 0f)
            }
        }

        return ResolvedLocation(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZONE_HE, true, "DEFAULT", mode, "Absolute_Default", 0f)
    }

    private fun savePersistentLocation(lat: Double, lng: Double, zoneHe: String) {
        StatusManager.updateLocation(context, zoneHe, lat, lng)
    }

    /**
     * Compatibility wrapper for existing background service logic
     */
    fun getCurrentLocationSync(): Pair<Double, Double> {
        val res = resolveCurrentLocation()
        return Pair(res.lat, res.lng)
    }
    
    // Legacy support for UI
    fun getManualLat(): String? = sharedPreferences.getString("manual_lat", null)
    fun getManualLng(): String? = sharedPreferences.getString("manual_lng", null)
}
