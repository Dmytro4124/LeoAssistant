package com.dmytro.leo.tools

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** What tools may do with the phone. Tools stay pure Kotlin; tests use a fake. */
interface PhoneActions {
    /** Opens an installed app by package name ([fresh]: on its start page, not where it was left). False if it can't be launched. */
    fun openApp(packageName: String, fresh: Boolean = false): Boolean

    /** Opens a link (geo:, google.navigation:, https:…), optionally in a specific app. */
    fun openUri(uri: String, packageName: String? = null): Boolean

    /** Opens web search results for [query] (ACTION_WEB_SEARCH) in [packageName]. */
    fun webSearch(query: String, packageName: String): Boolean
}

class AndroidPhoneActions(context: Context) : PhoneActions {

    private val appContext = context.applicationContext

    override fun openApp(packageName: String, fresh: Boolean): Boolean {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        if (fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return start(intent)
    }

    override fun openUri(uri: String, packageName: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (packageName != null) intent.setPackage(packageName)
        return start(intent)
    }

    override fun webSearch(query: String, packageName: String): Boolean =
        start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).setPackage(packageName))

    private fun start(intent: Intent): Boolean = try {
        // Started from the application context, so it needs its own task
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
