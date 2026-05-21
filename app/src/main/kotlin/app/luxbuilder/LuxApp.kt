package app.luxbuilder

import android.app.Application

class LuxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // No native lib to load; nothing else to bootstrap in v1.
    }
}
