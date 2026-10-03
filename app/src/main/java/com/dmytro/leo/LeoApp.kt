package com.dmytro.leo

import android.app.Application

class LeoApp : Application() {
    val container by lazy { AppContainer(this) }
}
