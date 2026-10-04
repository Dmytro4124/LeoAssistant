package com.dmytro.leo.ui

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import com.dmytro.leo.R
import com.dmytro.leo.widget.LeoTileService
import com.dmytro.leo.widget.LeoWidget

/** Asks the launcher to put the mic widget on the home screen (it shows its own dialog). */
fun Context.requestLeoWidget() {
    val manager = getSystemService(AppWidgetManager::class.java)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(this, LeoWidget::class.java), null, null)
    } else {
        Toast.makeText(this, "Додай віджет вручну: довге натискання на головному екрані → Віджети → Leo", Toast.LENGTH_LONG).show()
    }
}

/** Asks the system to add the "Leo" tile to Quick Settings (Android 13+), otherwise explains how. */
fun Context.requestLeoTile() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, LeoTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_widget_mic),
            mainExecutor,
        ) { }
    } else {
        Toast.makeText(this, "Додай плитку вручну: шторка → олівець → «Leo»", Toast.LENGTH_LONG).show()
    }
}
