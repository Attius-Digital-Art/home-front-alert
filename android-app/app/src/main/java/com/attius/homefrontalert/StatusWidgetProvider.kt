package com.attius.homefrontalert

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews

class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val sharedPrefs = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
            val status = sharedPrefs.getString("dash_status", "GREEN") ?: "GREEN"
            val rawZone = sharedPrefs.getString("current_home_zone", "Detecting...") ?: "Detecting..."
            
            val calc = ZoneDistanceCalculator(context)
            val localizedZone = calc.getLocalizedName(rawZone)

            val statusColor = when (status) {
                "RED" -> Color.parseColor("#FF3B30")
                "ORANGE" -> Color.parseColor("#FF9500")
                "YELLOW" -> Color.parseColor("#FFD60A")
                else -> Color.parseColor("#34C759")
            }

            val statusResId = when (status) {
                "RED" -> R.string.critical_status
                "ORANGE" -> R.string.warning_status
                "YELLOW" -> R.string.threat_status
                else -> R.string.no_alerts
            }

            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            
            views.setTextViewText(R.id.widget_status_text, context.getString(statusResId))
            views.setTextColor(R.id.widget_status_text, statusColor)
            
            // Clean up the format "Zone: %s" for widget display
            val zoneLabel = context.getString(R.string.status_saved_zone).split(":")[0]
            views.setTextViewText(R.id.widget_zone_text, "$zoneLabel: $localizedZone")

            val bgAlpha = if (status == "GREEN") 20 else 50
            val bgColor = Color.argb(bgAlpha, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
            views.setInt(R.id.widget_status_ring, "setBackgroundColor", bgColor)

            val intent = Intent(context, MainActivity::class.java).apply { 
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK 
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = android.content.ComponentName(context, StatusWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
            for (id in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, id)
            }
        }
    }
}
