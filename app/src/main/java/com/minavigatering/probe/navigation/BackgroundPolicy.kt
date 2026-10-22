package com.minavigatering.probe.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Package of the official companion app whose background policy also gates the bridge. */
const val MI_FITNESS_PACKAGE = "com.mi.health"

/** True when this app is already exempt from battery optimization. */
fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** The direct in-app request for the exemption, scoped to this package. */
fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))

/** The battery-optimization settings page, used when the direct request is refused. */
fun batterySettingsFallbackIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

/** The official companion app's details page, so its background policy can be checked. */
fun miFitnessAppDetailsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$MI_FITNESS_PACKAGE"))

/**
 * Starts [intent] from any context.
 *
 * A non-activity context gets `NEW_TASK`, so a service or receiver can never crash with an
 * activity-context requirement. A missing activity or a refused launch returns false instead
 * of propagating, which lets the caller fall back.
 */
fun startActivitySafely(context: Context, intent: Intent): Boolean {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/**
 * Asks for the battery-optimization exemption: the direct request first, the battery settings
 * page as fallback. Returns false only when both are refused; it never recurses.
 */
fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
    if (startActivitySafely(context, requestIgnoreBatteryOptimizationsIntent(context))) return true
    return startActivitySafely(context, batterySettingsFallbackIntent())
}
