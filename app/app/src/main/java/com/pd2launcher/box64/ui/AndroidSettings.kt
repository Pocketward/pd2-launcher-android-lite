package com.pd2launcher.box64.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** What Settings → App → Android says about the system settings the app depends on. */
data class SystemState(val batteryRestricted: Boolean, val notificationsOff: Boolean)

/**
 * Settings → App → Android as the screens show it: how the settings stand ([state], read again
 * each time the activity comes back) and the system screens that change them.
 */
class SystemUi(val onBatterySettings: () -> Unit, val onNotificationSettings: () -> Unit) {
    var state by mutableStateOf(SystemState(batteryRestricted = false, notificationsOff = false))
}

/** Settings → App → Android for the screens that show it; null where the app provides none (previews). */
val LocalSystem = staticCompositionLocalOf<SystemUi?> { null }
