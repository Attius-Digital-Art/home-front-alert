package com.attius.homefrontalert

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
    val timestampMs: Long = System.currentTimeMillis()
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
        
        // Static memory cache shared across all instances (SSOT)
        @Volatile
        private var sharedCachedLocation: ResolvedLocation? = null
    }

    fun getTrackingMode(): LocationTrackingMode {
        val modeStr = sharedPreferences.getString("tracking_mode", LocationTrackingMode.GPS_LIVE.name)
        return try { LocationTrackingMode.valueOf(modeStr!!) } catch (e: Exception) { LocationTrackingMode.GPS_LIVE }
    }

    fun setTrackingMode(mode: LocationTrackingMode) {
        sharedPreferences.edit().putString("tracking_mode", mode.name).apply()
        // Clear session cache when switching modes to prevent SSOT violations (e.g. old GPS showing in Fixed mode)
        cachedLocation = null
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

        // 2. LIVE GPS MODE: SSOT Chain
        try {
            // A. Recent Session Cache (Fast UI return)
            getSessionCache()?.let { return it.copy(activeMode = mode) }

            // B. System Last Known (Check if fresh)
            val lastLocTask = fusedLocationClient.lastLocation
            val lastLoc: Location? = Tasks.await(lastLocTask, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (lastLoc != null) {
                val age = System.currentTimeMillis() - lastLoc.time
                if (age < 180000) { // 3 min 
                    val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(lastLoc.latitude, lastLoc.longitude)
                    if (zoneInfo != null) {
                        val res = ResolvedLocation(lastLoc.latitude, lastLoc.longitude, zoneInfo.first, false, "GPS", mode, lastLoc.provider ?: "last_known", lastLoc.accuracy)
                        updateSessionCache(res)
                        savePersistentLocation(res.lat, res.lng, res.zoneNameHe)
                        return res
                    }
                }
            }

            // C. Fresh High-Accuracy Lock (Wait up to 10s for satellites)
            val freshTask = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val freshLoc: Location? = Tasks.await(freshTask, 10, java.util.concurrent.TimeUnit.SECONDS)
            if (freshLoc != null) {
                val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(freshLoc.latitude, freshLoc.longitude)
                if (zoneInfo != null) {
                    val res = ResolvedLocation(freshLoc.latitude, freshLoc.longitude, zoneInfo.first, false, "GPS", mode, freshLoc.provider ?: "gps_sat", freshLoc.accuracy)
                    updateSessionCache(res)
                    savePersistentLocation(res.lat, res.lng, res.zoneNameHe)
                    return res
                }
            }

            // D. Indoor Fallback (Cell/WiFi) - 5s
            val indoorTask = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            val indoorLoc: Location? = Tasks.await(indoorTask, 5, java.util.concurrent.TimeUnit.SECONDS)
            if (indoorLoc != null) {
                val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(indoorLoc.latitude, indoorLoc.longitude)
                if (zoneInfo != null) {
                    val res = ResolvedLocation(indoorLoc.latitude, indoorLoc.longitude, zoneInfo.first, false, "GPS", mode, "indoor_" + (indoorLoc.provider ?: "net"), indoorLoc.accuracy)
                    updateSessionCache(res)
                    savePersistentLocation(res.lat, res.lng, res.zoneNameHe)
                    return res
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "GPS Chain failed", e)
        }

        // 3. FAILBACKS (In GPS mode but searching failed)
        
        // 3a. Persistent Persistence (Last known successfully verified location)
        val pLat = sharedPreferences.getString("last_known_lat", null)?.toDoubleOrNull()
        val pLng = sharedPreferences.getString("last_known_lng", null)?.toDoubleOrNull()
        val pZone = sharedPreferences.getString("last_known_zone_he", null)
        if (pLat != null && pLng != null && pZone != null) {
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
