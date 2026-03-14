package com.attius.homefrontalert

import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.attius.homefrontalert.BuildConfig

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private lateinit var tvDashStatus: TextView
    private lateinit var tvDashTimer: TextView
    private lateinit var tvLocationBadge: TextView
    private lateinit var tvLocationZone: TextView
    private lateinit var ivLocationStatus: ImageView
    private lateinit var vStatusDot: android.view.View
    private lateinit var ivShield: ImageView
    private lateinit var vStatusRing: android.view.View
    private lateinit var layoutDashboardContent: ConstraintLayout
    
    private lateinit var tvLastAlertZones: TextView
    private lateinit var tvLastAlertInfo: TextView
    private lateinit var cardLastAlert: androidx.cardview.widget.CardView
    private lateinit var sharedPrefs: android.content.SharedPreferences
    private lateinit var locationManager: AppLocationManager
    private lateinit var distanceCalculator: ZoneDistanceCalculator

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isResolvingLocation = false
    private val uiUpdater = object : Runnable {
        override fun run() {
            refreshDashboardState()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val isRtl = LocaleHelper.getLanguage(this) == "iw"
        window.decorView.layoutDirection = if (isRtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
        
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        locationManager = AppLocationManager(this)
        distanceCalculator = ZoneDistanceCalculator(this)

        performInitialSetupIfNeeded()

        if (!sharedPrefs.getBoolean("tos_accepted", false)) {
            startActivity(Intent(this, TOSActivity::class.java))
        }

        bindViews()
        startPollingServiceIfEnabled()

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnHelp).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(android.text.Html.fromHtml(getString(R.string.help_content)))
                .setPositiveButton("OK", null)
                .show()
        }

        refreshDashboardState()
        setVersionBadge()
    }

    private fun performInitialSetupIfNeeded() {
        if (!sharedPrefs.getBoolean("initial_setup_v135", false)) {
            sharedPrefs.edit().apply {
                putFloat("alert_volume", 0.5f) // As agreed
                putString("tracking_mode", LocationTrackingMode.GPS_LIVE.name)
                putString("fixed_zone_he", AppLocationManager.DEFAULT_ZONE_HE)
                putBoolean("shield_active", true) // Core feature: ON by default
                putBoolean("initial_setup_v135", true)
                putLong("dash_status_start_ms", System.currentTimeMillis())
                apply()
            }
        }
    }

    private fun bindViews() {
        tvDashStatus = findViewById(R.id.tvDashStatus)
        tvDashTimer = findViewById(R.id.tvDashTimer)
        tvLocationBadge = findViewById(R.id.tvLocationBadge)
        tvLocationZone = findViewById(R.id.tvLocationZone)
        ivLocationStatus = findViewById(R.id.ivLocationStatus)
        vStatusDot = findViewById(R.id.vStatusDot)
        ivShield = findViewById(R.id.ivShield)
        vStatusRing = findViewById(R.id.vStatusRing)
        layoutDashboardContent = findViewById(R.id.layoutDashboardContent)
        
        tvLastAlertZones = findViewById(R.id.tvLastAlertZones)
        tvLastAlertInfo = findViewById(R.id.tvLastAlertInfo)
        cardLastAlert = findViewById(R.id.cardLastAlert)

        tvLocationBadge.setOnClickListener { showLocationExplanation() }
        tvLocationZone.setOnClickListener { showLocationExplanation() }
        ivLocationStatus.setOnClickListener { showLocationExplanation() }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiUpdater)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiUpdater)
    }

    private fun refreshDashboardState() {
        val status = sharedPrefs.getString("dash_status", "GREEN") ?: "GREEN"
        val startTime = sharedPrefs.getLong("dash_status_start_ms", System.currentTimeMillis())
        
        if (status != "GREEN" && System.currentTimeMillis() - startTime > 1800000) {
            StatusManager.updateStatus(this, "GREEN")
            return
        }

        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
        val unitS = getString(R.string.unit_seconds_short)
        val unitM = getString(R.string.unit_minutes_short)
        val unitH = getString(R.string.unit_hours_short)
        
        val dispTimer = when {
            elapsedSec < 60 -> "${elapsedSec}$unitS"
            elapsedSec < 3600 -> String.format("%d:%02d", elapsedSec / 60, elapsedSec % 60)
            else -> String.format("%d$unitH %d$unitM", elapsedSec / 3600, (elapsedSec % 3600) / 60)
        }
        
        val timerPrefix = if (status == "GREEN") getString(R.string.monitoring_for) else getString(R.string.active_for)
        tvDashTimer.text = "$timerPrefix $dispTimer"
        
        val statusColor = when(status) {
            "RED" -> Color.parseColor("#FF3B30")
            "ORANGE" -> Color.parseColor("#FF9500")
            "YELLOW" -> Color.parseColor("#FFD60A")
            else -> Color.parseColor("#34C759")
        }

        updateDashboardBackground(statusColor)
        updateStatusTextAndIcons(status, statusColor)
        refreshLocationStatus()
        refreshLastAlertHistory()
        StatusWidgetProvider.updateAllWidgets(this)
    }

    private fun refreshLocationStatus() {
        if (isResolvingLocation) return
        isResolvingLocation = true
        
        // Run location resolution in a background thread
        kotlin.concurrent.thread(start = true) {
            try {
                val res = locationManager.resolveCurrentLocation()
                val localizedName = distanceCalculator.getLocalizedName(res.zoneNameHe)
                
                uiHandler.post {
                    tvLocationZone.text = localizedName
                    if (res.source == "GPS") {
                        ivLocationStatus.setImageResource(R.drawable.ic_gps_antenna)
                        ivLocationStatus.imageTintList = ColorStateList.valueOf(Color.parseColor("#34C759"))
                    } else {
                        ivLocationStatus.setImageResource(R.drawable.ic_manual_location)
                        val color = if (res.source == "SAVED") Color.parseColor("#546E7A") else Color.parseColor("#424242")
                        ivLocationStatus.imageTintList = ColorStateList.valueOf(color)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeFrontAlerts", "Location thread failed", e)
            } finally {
                isResolvingLocation = false
            }
        }
    }

    private fun showLocationExplanation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || 
                          lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        kotlin.concurrent.thread(start = true) {
            val res = locationManager.resolveCurrentLocation()
            uiHandler.post {
                var title = when(res.source) {
                    "GPS" -> "Live GPS Active"
                    "SAVED" -> if (res.isFallback) "Using Saved Location" else "Fixed mode"
                    else -> "Default Location"
                }
                
                var message = when(res.source) {
                    "GPS" -> "Your location is being updated in real-time."
                    "SAVED" -> if (res.isFallback) "Unable to get a fresh GPS lock. Using your saved location temporarily." else "Using the manually selected zone."
                    else -> "No location set. Using Jerusalem as default."
                }

                if (!isGpsEnabled && locationManager.isUsingLiveGps()) {
                    title = "GPS is Disabled"
                    message = "System-level GPS is turned off. The app cannot track your location automatically.\n\nClick 'Fix' to turn it on."
                }

                val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                
                if (!isGpsEnabled && locationManager.isUsingLiveGps()) {
                    builder.setPositiveButton("Fix") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    builder.setNegativeButton("Close", null)
                } else {
                    builder.setPositiveButton(R.string.settings_title) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                    builder.setNegativeButton("OK", null)
                }
                builder.show()
            }
        }
    }

    private fun updateDashboardBackground(statusColor: Int) {
        val cardBg = Color.parseColor("#181818")
        val borderPx = (2 * resources.displayMetrics.density).toInt()
        val border = GradientDrawable().apply {
            setColor(cardBg)
            setStroke(borderPx, statusColor)
            cornerRadius = 24f * resources.displayMetrics.density
        }
        layoutDashboardContent.background = border

        val glowAlpha = if (statusColor == Color.parseColor("#34C759")) 20 else 50
        val glowColor = Color.argb(glowAlpha, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
        vStatusRing.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(glowColor)
        }
    }

    private fun updateStatusTextAndIcons(status: String, statusColor: Int) {
        tvDashStatus.text = when(status) {
            "RED" -> getString(R.string.critical_status)
            "ORANGE" -> getString(R.string.warning_status)
            "YELLOW" -> getString(R.string.threat_status)
            else -> getString(R.string.no_alerts)
        }
        tvDashStatus.setTextColor(statusColor)
        vStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(statusColor)
        
        if (status == "GREEN") {
            ivShield.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.2f) })
            ivShield.alpha = 0.7f
        } else {
            ivShield.clearColorFilter()
            ivShield.alpha = 1.0f 
        }
    }

    private fun startPollingServiceIfEnabled() {
        val hasTos = sharedPrefs.getBoolean("tos_accepted", false)
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (hasTos && hasLocation && sharedPrefs.getBoolean("shield_active", false)) {
            val intent = Intent(this, LocalPollingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun setVersionBadge() {
        val tvVersionBadge = findViewById<TextView>(R.id.tvVersionBadge)
        val tvAlphaBadge = findViewById<TextView>(R.id.tvAlphaBadge)
        
        if (BuildConfig.IS_PAID) {
            tvVersionBadge.visibility = android.view.View.VISIBLE
            tvVersionBadge.text = "PRO"
        } else {
            tvVersionBadge.visibility = android.view.View.GONE
        }

        // Always show alpha badge for internal testing phase
        tvAlphaBadge.visibility = android.view.View.VISIBLE
    }

    private fun refreshLastAlertHistory() {
        val zones = sharedPrefs.getString("last_alert_zones", null)
        val dist = sharedPrefs.getFloat("last_alert_dist", -1f)
        val time = sharedPrefs.getLong("last_alert_time", 0L)
        
        if (zones != null && time > 0) {
            cardLastAlert.visibility = android.view.View.VISIBLE
            val localizedZones = zones.split(", ").joinToString(", ") { distanceCalculator.getLocalizedName(it) }
            tvLastAlertZones.text = localizedZones
            
            val diffMs = System.currentTimeMillis() - time
            val diffMin = diffMs / 60000
            val timeText = when {
                diffMin < 1 -> getString(R.string.just_now)
                diffMin < 60 -> getString(R.string.detected_ago, "${diffMin}${getString(R.string.unit_minutes_short)}")
                else -> android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(time))
            }
            val distText = if (dist < 0) getString(R.string.remote_alert) else getString(R.string.distance, String.format("%.1f", dist))
            tvLastAlertInfo.text = "$timeText • $distText"
        } else {
            cardLastAlert.visibility = android.view.View.GONE
        }
    }
}
