package com.phonebuttons.control.phone_volume

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channelName = "volume_buttons/overlay"
    private val notifRequestCode = 4242

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "hasOverlayPermission" ->
                    result.success(Settings.canDrawOverlays(this))

                "requestNotificationPermission" -> {
                    requestNotificationPermissionIfNeeded()
                    result.success(null)
                }

                "askBatteryOptimizationIfNeeded" -> {
                    askBatteryOptimizationIfNeeded()
                    result.success(null)
                }

                "openOverlaySettings" -> {
                    openOverlaySettings()
                    result.success(null)
                }

                "startOverlay" -> result.success(startOverlay())
                "stopOverlay" -> result.success(stopOverlay())
                "isOverlayEnabled" ->
                    result.success(prefs().getBoolean(VolumeOverlayService.KEY_ENABLED, false))

                else -> result.notImplemented()
            }
        }
    }

    private fun prefs() = getSharedPreferences(VolumeOverlayService.PREF, MODE_PRIVATE)

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                notifRequestCode,
            )
        }
    }

    /**
     * Non-blocking, once-per-install prompt: asks the system to exclude the app from
     * battery optimization. Never re-asks even if the user dismisses it.
     */
    private fun askBatteryOptimizationIfNeeded() {
        val alreadyAsked = prefs().getBoolean("battery_optimization_asked", false)
        if (alreadyAsked) return
        prefs().edit().putBoolean("battery_optimization_asked", true).apply()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: Exception) {
            // Not fatal — the button works fine, it's just not exempt from battery savings.
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun startOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        prefs().edit().putBoolean(VolumeOverlayService.KEY_ENABLED, true).apply()
        startForegroundService(Intent(this, VolumeOverlayService::class.java))
        return true
    }

    private fun stopOverlay(): Boolean {
        prefs().edit().putBoolean(VolumeOverlayService.KEY_ENABLED, false).apply()
        stopService(Intent(this, VolumeOverlayService::class.java))
        return true
    }
}
