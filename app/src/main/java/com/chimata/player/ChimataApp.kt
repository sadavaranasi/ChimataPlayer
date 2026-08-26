package com.chimata.player

import android.app.Application
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Security

class ChimataApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Conscrypt properly builds/completes certificate chains (fetching a missing
        // intermediate CA cert the way a browser does) instead of failing outright the way
        // Android's default TLS stack can on servers that don't send their full chain.
        // This still fully validates against real trusted root certificates - it does not
        // disable or weaken certificate checking in any way.
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            Log.d("ChimataPlayer", "Conscrypt TLS provider installed")
        } catch (t: Throwable) {
            Log.e("ChimataPlayer", "Failed to install Conscrypt provider", t)
        }
    }
}
