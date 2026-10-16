package com.theveloper.pixelplay

import android.util.Log
import timber.log.Timber

/**
 * Timber tree for release builds.
 *
 * Only logs WARN/ERROR/WTF — DEBUG/INFO/VERBOSE are stripped by R8
 * via -assumenosideeffects rules in proguard-rules.pro.
 *
 * BUG FIX: previously had a TODO to report errors to crash analytics.
 * Now actually reports ERROR-level logs to Sentry if the SDK is initialized
 * (DSN was provided at build time).
 */
class ReleaseTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        return priority >= Log.WARN
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Report ERROR and higher to Sentry if available
        if (priority >= Log.ERROR) {
            try {
                if (BuildConfig.SENTRY_DSN.isNotBlank()) {
                    if (t != null) {
                        io.sentry.Sentry.captureException(t)
                    } else {
                        io.sentry.Sentry.captureMessage(message, io.sentry.SentryLevel.ERROR)
                    }
                }
            } catch (_: Exception) {
                // Sentry not initialized — silent fail
            }
        }
    }
}
