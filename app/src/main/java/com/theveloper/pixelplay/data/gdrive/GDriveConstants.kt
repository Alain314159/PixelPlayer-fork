package com.theveloper.pixelplay.data.gdrive

import com.theveloper.pixelplay.BuildConfig

/**
 * Google Drive API credentials.
 *
 * BUG FIX: previously this was a hardcoded placeholder string
 * "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com" that made GDrive login
 * always fail. Now reads from BuildConfig, which is set at build time
 * from the GDRIVE_WEB_CLIENT_ID environment variable or local.properties.
 *
 * To enable GDrive: set GDRIVE_WEB_CLIENT_ID in your env or local.properties:
 *   GDRIVE_WEB_CLIENT_ID=your-client-id.apps.googleusercontent.com
 */
val WEB_CLIENT_ID: String
    get() = BuildConfig.GDRIVE_WEB_CLIENT_ID.ifBlank {
        "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
    }
