package com.selteck.so2project

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.selteck.so2project.automation.So2AccessibilityService

object AccessibilityPermission {
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(context, So2AccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
}
