package com.theveloper.pixelplay

import android.app.Application
import com.theveloper.pixelplay.data.WearConnectivityRepository
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class WearApp : Application() {

    /**
     * Started in [onCreate] so the watch is already watching for the companion phone
     * capability before any UI screen subscribes to connectivity state. This is what
     * makes the "is the phone reachable?" indicator update reactively on Wear OS 6 —
     * previously the watch relied on the result of the first outgoing message, which
     * was racy.
     */
    @Inject
    lateinit var connectivityRepository: WearConnectivityRepository

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        connectivityRepository.start()
    }
}
