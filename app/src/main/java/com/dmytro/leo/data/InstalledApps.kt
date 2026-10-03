package com.dmytro.leo.data

import android.content.Context
import android.content.Intent

data class AppEntry(val label: String, val packageName: String)

/** Apps that have an icon in the launcher. */
fun interface InstalledApps {
    fun list(): List<AppEntry>
}

class AndroidInstalledApps(context: Context) : InstalledApps {

    private val appContext = context.applicationContext

    // Visible thanks to the MAIN/LAUNCHER <queries> entry in the manifest
    override fun list(): List<AppEntry> {
        val pm = appContext.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != appContext.packageName }
            .distinctBy { it.packageName }
    }
}
