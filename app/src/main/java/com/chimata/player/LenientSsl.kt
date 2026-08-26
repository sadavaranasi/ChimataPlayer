package com.chimata.player

import android.util.Log
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * chimatamusic.us serves a broken/invalid TLS certificate (confirmed: it fails even with
 * Conscrypt's proper chain-building, meaning the problem isn't a missing intermediate cert -
 * the cert itself is invalid). This app only ever talks to that one domain, so rather than
 * disabling certificate checking app-wide, this builds a client that skips validation
 * specifically for requests to chimatamusic.us (and its www variant) and nothing else.
 *
 * Security tradeoff, stated plainly: this removes protection against a network-level attacker
 * tampering with or impersonating chimatamusic.us specifically (e.g. on an untrusted public
 * Wi-Fi). For a hobby app that only streams public, non-sensitive audio files (no login, no
 * personal data ever sent), that's a small and deliberate risk - but it is a real one, so this
 * is opt-in and isolated rather than silent or global.
 */
object LenientSsl {

    private const val TAG = "ChimataPlayer"
    private const val TRUSTED_HOST_SUFFIX = "chimatamusic.us"

    private val trustAllCerts = arrayOf<X509TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            Log.w(TAG, "Skipping cert validation for chimatamusic.us (known-broken cert)")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val hostnameVerifier = HostnameVerifier { hostname, _ ->
        hostname.endsWith(TRUSTED_HOST_SUFFIX)
    }

    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0])
            .hostnameVerifier(hostnameVerifier)
    }
}
