package com.theveloper.pixelplay.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date
import java.util.Locale

/**
 * Data class representing a saved crash log entry.
 */
data class CrashLogData(
    val timestamp: Long,
    val formattedDate: String,
    val exceptionMessage: String,
    val stackTrace: String
) {
    /**
     * Returns the full crash log formatted for display or sharing.
     */
    fun getFullLog(): String {
        return buildString {
            appendLine("=== PixelPlayer Crash Report ===")
            appendLine("Date: $formattedDate")
            appendLine("Exception: $exceptionMessage")
            appendLine()
            appendLine("Stack Trace:")
            appendLine(stackTrace)
        }
    }
}

/**
 * Custom UncaughtExceptionHandler that saves crash information to SharedPreferences
 * so it can be displayed to the user when the app restarts.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val PREFS_NAME = "crash_handler_prefs"
    private const val KEY_HAS_CRASH = "has_crash"
    private const val KEY_TIMESTAMP = "crash_timestamp"
    private const val KEY_EXCEPTION_MESSAGE = "crash_exception_message"
    private const val KEY_STACK_TRACE = "crash_stack_trace"

    private lateinit var appContext: Context
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Installs this crash handler as the default uncaught exception handler.
     * Should be called in Application.onCreate().
     *
     * If a Sentry DSN is configured (BuildConfig.SENTRY_DSN non-empty), the SDK is
     * initialized first so it captures the crash via its own mechanism; this handler
     * still saves a local copy to SharedPreferences for the in-app crash dialog.
     */
    fun install(context: Context) {
        appContext = context.applicationContext

        // Initialize Sentry SDK if a DSN was provided at build time.
        // If SENTRY_DSN is empty, this is a no-op (the SDK is on the classpath but inactive).
        try {
            val dsn = com.theveloper.pixelplay.BuildConfig.SENTRY_DSN
            if (!dsn.isNullOrBlank()) {
                io.sentry.SentryAndroid.init(context) { options ->
                    options.dsn = dsn
                    // Sample rate: 1.0 = capture all crashes.
                    // Lower this if the app becomes high-traffic.
                    options.sampleRate = 1.0
                    // Traces sample rate (for performance monitoring) — keep low to avoid
                    // overhead. Set to 0 to disable.
                    options.tracesSampleRate = 0.0
                    // Attach screenshots — disabled by default for privacy.
                    options.attachScreenshots = false
                    // Attach view hierarchy on crash — useful for debugging.
                    options.attachViewHierarchy = true
                    // Before-send hook: redact sensitive data before sending to Sentry.
                    // Reuses CrashLogRedactor so the same redaction rules apply as the local
                    // SharedPreferences crash log.
                    options.beforeSend = SentryBeforeSendCallback@ { event, _ ->
                        val redactedMessage = event.message?.let { CrashLogRedactor.redact(it.message) }
                        if (redactedMessage != null) {
                            event.message = io.sentry.protocol.Message().apply { message = redactedMessage }
                        }
                        event
                    }
                }
                // Register sentry-android-timber so all Timber.e() / Timber.w() calls
                // automatically become Sentry breadcrumbs. (Implementation: the
                // SentryTimberTree is auto-registered by the SDK on init.)
                timber.log.Timber.tag("CrashHandler").i("Sentry initialized with DSN ${dsn.take(20)}...")
            }
        } catch (e: Exception) {
            // Defensive: don't crash the app if Sentry init fails.
            timber.log.Timber.tag("CrashHandler").w(e, "Sentry initialization failed (non-fatal)")
        }

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashLog(throwable)
            // Capture the exception via Sentry if it's enabled (DSN was provided at build time).
            // Sentry SDK has its own UncaughtExceptionHandler integration that normally handles
            // this, but we call captureException explicitly to ensure the event is sent even
            // if the SDK's auto-capture is delayed by the shutdown sequence.
            if (com.theveloper.pixelplay.BuildConfig.SENTRY_DSN.isNotBlank()) {
                io.sentry.Sentry.captureException(throwable)
                // Force flush — the process is about to die.
                io.sentry.Sentry.flush(2000)
            }
        } catch (e: Exception) {
            // Ignore any errors during crash saving
        }

        // Call the default handler to allow normal crash behavior
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashLog(throwable: Throwable) {
        val timestamp = System.currentTimeMillis()
        val rawStackTrace = getStackTraceString(throwable)
        val rawExceptionMessage = throwable.message ?: throwable.javaClass.simpleName

        // Throwable messages from network / media stacks routinely embed
        // bearer tokens, salted Subsonic params, Google API keys, and the
        // user's Telegram phone number. Redact before persisting so the
        // share-intent in CrashReportDialog cannot leak them outward.
        val stackTrace = CrashLogRedactor.redact(rawStackTrace)
        val exceptionMessage = CrashLogRedactor.redact(rawExceptionMessage)

        // Use commit() instead of apply() to ensure data is written synchronously
        // before the process terminates
        prefs.edit().apply {
            putBoolean(KEY_HAS_CRASH, true)
            putLong(KEY_TIMESTAMP, timestamp)
            putString(KEY_EXCEPTION_MESSAGE, exceptionMessage)
            putString(KEY_STACK_TRACE, stackTrace)
            commit() // Synchronous write - ensures data is saved before process dies
        }
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    /**
     * Checks if there is a saved crash log from a previous session.
     */
    fun hasCrashLog(): Boolean {
        if (!::appContext.isInitialized) return false
        return prefs.getBoolean(KEY_HAS_CRASH, false)
    }

    /**
     * Retrieves the saved crash log data.
     * Returns null if no crash log exists.
     */
    fun getCrashLog(): CrashLogData? {
        if (!hasCrashLog()) return null

        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)
        val rawExceptionMessage = prefs.getString(KEY_EXCEPTION_MESSAGE, "Unknown error") ?: "Unknown error"
        val rawStackTrace = prefs.getString(KEY_STACK_TRACE, "") ?: ""
        // Defensively redact again on read so any crash entry persisted by
        // an older build (before redaction landed) is sanitized before the
        // share-intent surface in CrashReportDialog sees it.
        val exceptionMessage = CrashLogRedactor.redact(rawExceptionMessage)
        val stackTrace = CrashLogRedactor.redact(rawStackTrace)

        // Use DateTimeFormatter (thread-safe) instead of SimpleDateFormat (not thread-safe).
        val formattedDate = java.time.format.DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(timestamp))

        return CrashLogData(
            timestamp = timestamp,
            formattedDate = formattedDate,
            exceptionMessage = exceptionMessage,
            stackTrace = stackTrace
        )
    }

    /**
     * Clears the saved crash log.
     * Should be called after the user has acknowledged the crash report.
     */
    fun clearCrashLog() {
        if (!::appContext.isInitialized) return
        prefs.edit().clear().apply()
    }
}
