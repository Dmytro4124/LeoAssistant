package com.dmytro.leo.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/** Makes the system wake Leo at a reminder's time or when the user comes to its place. */
interface ReminderScheduler {
    fun schedule(reminder: Reminder)
    fun cancel(reminder: Reminder)

    /** Exact times need the "alarms & reminders" permission (granted by default for Leo). */
    fun canUseExactTimes(): Boolean = true

    /** Places need location access "all the time". */
    fun canUsePlaces(): Boolean = true
}

/** Turns "дім", "АТБ на Князів Коріатовичів, Кам'янець" into coordinates. */
fun interface PlaceFinder {
    suspend fun find(query: String): Place?
}

class AndroidReminderScheduler(context: Context) : ReminderScheduler {

    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(AlarmManager::class.java)
    private val location = appContext.getSystemService(LocationManager::class.java)

    override fun schedule(reminder: Reminder) {
        cancel(reminder)
        reminder.at?.let { at ->
            val intent = pending(ReminderReceiver.ACTION_FIRE, reminder.id, mutable = false)
            if (canUseExactTimes()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                // Without the permission it may come a few minutes late, but it comes
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }
        reminder.place?.let { place ->
            if (!canUsePlaces()) return@let
            // The system adds whether we're entering or leaving, so the intent must be mutable
            @SuppressLint("MissingPermission") // checked in canUsePlaces()
            val added = runCatching {
                location.addProximityAlert(
                    place.latitude, place.longitude, place.radiusM, -1,
                    pending(ReminderReceiver.ACTION_NEAR, reminder.id, mutable = true),
                )
            }
            added.exceptionOrNull()?.let { /* no location provider right now: re-added on the next start */ }
        }
    }

    override fun cancel(reminder: Reminder) {
        alarms.cancel(pending(ReminderReceiver.ACTION_FIRE, reminder.id, mutable = false))
        if (canUsePlaces()) {
            @SuppressLint("MissingPermission")
            val removed = runCatching { location.removeProximityAlert(pending(ReminderReceiver.ACTION_NEAR, reminder.id, mutable = true)) }
            removed.exceptionOrNull()
        }
    }

    override fun canUseExactTimes(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    override fun canUsePlaces(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun pending(action: String, id: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(appContext, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(appContext, id, intent, flags)
    }
}

class AndroidPlaceFinder(context: Context) : PlaceFinder {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION") // the blocking call is simplest here, and it runs on the IO dispatcher
    override suspend fun find(query: String): Place? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            Geocoder(appContext, Locale.forLanguageTag("uk-UA")).getFromLocationName(query, 1)?.firstOrNull()
                ?.let { Place(name = query, latitude = it.latitude, longitude = it.longitude) }
        } catch (e: IOException) {
            null
        }
    }
}
