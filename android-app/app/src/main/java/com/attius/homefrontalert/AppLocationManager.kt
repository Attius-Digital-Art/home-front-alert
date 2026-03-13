package com.attius.homefrontalert

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
    val isFallback: Boolean, // True if we are using a substitute (old GPS, cached, or default) because real-time is missing
    val source: String,      // "GPS", "SAVED", "DEFAULT"
    val activeMode: LocationTrackingMode = LocationTrackingMode.FIXED_ZONE
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
    }

    fun getTrackingMode(): LocationTrackingMode {
        val modeStr = sharedPreferences.getString("tracking_mode", LocationTrackingMode.GPS_LIVE.name)
        return try { LocationTrackingMode.valueOf(modeStr!!) } catch (e: Exception) { LocationTrackingMode.GPS_LIVE }
    }

    fun setTrackingMode(mode: LocationTrackingMode) {
        sharedPreferences.edit().putString("tracking_mode", mode.name).apply()
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

    private var cachedLocation: ResolvedLocation? = null

    /**
     * Resolves the location based on current settings and environmental availability.
     */
    @SuppressLint("MissingPermission")
    fun resolveCurrentLocation(): ResolvedLocation {
        val mode = getTrackingMode()
        val savedZoneHe = getSavedZoneHe()
        
        // 1. If GPS Mode is active, try to get a real-time lock
        if (mode == LocationTrackingMode.GPS_LIVE) {
            try {
                // Try last known location first (near instant)
                val lastLocTask = fusedLocationClient.lastLocation
                val lastLoc: Location? = Tasks.await(lastLocTask, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                
                val ageMs = if (lastLoc != null) System.currentTimeMillis() - lastLoc.time else Long.MAX_VALUE
                if (lastLoc != null && ageMs < 1800000) { 
                    val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(lastLoc.latitude, lastLoc.longitude)
                    if (zoneInfo != null && zoneInfo.second < 150.0) {
                        savePersistentLocation(lastLoc.latitude, lastLoc.longitude, zoneInfo.first)
                        val res = ResolvedLocation(lastLoc.latitude, lastLoc.longitude, zoneInfo.first, false, "GPS")
                        cachedLocation = res
                        return res
                    }
                }

                // If not fresh or null, try a fresh high-accuracy hit
                val locationTask = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                val location: Location? = Tasks.await(locationTask, 6, java.util.concurrent.TimeUnit.SECONDS)
                
                if (location != null) {
                    val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(location.latitude, location.longitude)
                    if (zoneInfo != null && zoneInfo.second < 150.0) {
                        savePersistentLocation(location.latitude, location.longitude, zoneInfo.first)
                        val res = ResolvedLocation(location.latitude, location.longitude, zoneInfo.first, false, "GPS", mode)
                        cachedLocation = res
                        return res
                    }
                }
            } catch (e: Exception) {
                // Timeout or permission issue
            }
        }

        // 2. Memory Cache Fallback - If we HAD a location recently in this session, keep it!
        cachedLocation?.let { 
            return it.copy(isFallback = true, activeMode = mode)
        }

        // 3. Fallback to Saved Zone (Manual Setting)
        if (savedZoneHe != null) {
            val loc = distanceCalculator.getZoneCoordinates(savedZoneHe)
            if (loc != null) {
                val res = ResolvedLocation(loc.lat, loc.lng, savedZoneHe, mode == LocationTrackingMode.GPS_LIVE, "SAVED", mode)
                cachedLocation = res
                return res
            }
        }

        // 4. Persistence Fallback: The "Last Known" from previous sessions (Prevents Jerusalem Jump)
        val lastKnownLat = sharedPreferences.getString("last_known_lat", null)?.toDoubleOrNull()
        val lastKnownLng = sharedPreferences.getString("last_known_lng", null)?.toDoubleOrNull()
        val lastKnownZone = sharedPreferences.getString("last_known_zone_he", null)
        if (lastKnownLat != null && lastKnownLng != null && lastKnownZone != null) {
            return ResolvedLocation(lastKnownLat, lastKnownLng, lastKnownZone, mode == LocationTrackingMode.GPS_LIVE, "SAVED", mode)
        }

        // 5. Absolute Fallback: Jerusalem Museum (Only for total cold start)
        return ResolvedLocation(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZONE_HE, true, "DEFAULT", mode)
    }

    private fun savePersistentLocation(lat: Double, lng: Double, zoneHe: String) {
        sharedPreferences.edit()
            .putString("last_known_lat", lat.toString())
            .putString("last_known_lng", lng.toString())
            .putString("last_known_zone_he", zoneHe)
            .apply()
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
