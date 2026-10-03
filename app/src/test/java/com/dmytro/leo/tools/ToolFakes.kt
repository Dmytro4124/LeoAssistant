package com.dmytro.leo.tools

import com.dmytro.leo.data.PlacesStore

/** Records what tools tried to open; [available] decides which links/apps "work". */
class FakePhone(private val available: (uri: String, packageName: String?) -> Boolean = { _, _ -> true }) : PhoneActions {
    val openedApps = mutableListOf<String>()
    val openedUris = mutableListOf<Pair<String, String?>>()

    override fun openApp(packageName: String): Boolean {
        openedApps += packageName
        return true
    }

    override fun openUri(uri: String, packageName: String?): Boolean {
        if (!available(uri, packageName)) return false
        openedUris += uri to packageName
        return true
    }
}

class FakePlaces : PlacesStore {
    private val places = sortedMapOf<String, String>()
    override fun all(): Map<String, String> = places
    override fun save(name: String, address: String) {
        places[PlacesStore.normalizeName(name)] = address
    }
}
