package com.turbofan3360.openeq.audioprocessing

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class ForegroundServiceHandler(context: Context) {
    private val myContext = context
    private val foregroundServiceIntent: Intent by lazy { Intent(myContext, EqForegroundService::class.java) }

    private var latestEqLevels: MutableList<Float> = mutableListOf()
    private var latestGlobalAudio: Boolean = false
    private var latestBassBoost: Short = 0

    // Class to bind to the foreground service
    private var eqService: EqForegroundService? = null
    // Tracks whether bindService() has actually been called without a matching unbindService()
    // yet - eqService itself isn't reliable for this since it's set/cleared asynchronously
    // by the connection callbacks, independent of whether we're still bound.
    private var isBound = false
    private val connection = object : ServiceConnection {
        // FUNCTION EXECUTES ASYNCHRONOUSLY
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EqForegroundService.LocalBinder
            eqService = binder.getService()

            // Passing required data to service
            eqService?.updateEqLevels(latestEqLevels)
            eqService?.updateTryGlobalAudio(latestGlobalAudio)
            eqService?.updateBassBoost(latestBassBoost)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            isBound = false
        }
    }

    fun updateEqLevels(
        eqLevels: MutableList<Float>
    ) {
        latestEqLevels = eqLevels
        eqService?.updateEqLevels(latestEqLevels)
    }

    fun updateGlobalAudio(
        globalAudio: Boolean
    ) {
        latestGlobalAudio = globalAudio
        eqService?.updateTryGlobalAudio(latestGlobalAudio)
    }

    fun updateBassBoost(
        bassBoost: Short
    ) {
        latestBassBoost = bassBoost
        eqService?.updateBassBoost(latestBassBoost)
    }

    fun findMediaListenService(
        onEqEnabled: () -> Unit,
        eqLevels: MutableList<Float>,
        globalAudio: Boolean,
        bassBoost: Short = 0
    ) {
        // Checks to see if the foreground service is running; if so it re-binds to it
        if (EqForegroundService.isRunning) {
            latestEqLevels = eqLevels
            latestGlobalAudio = globalAudio
            latestBassBoost = bassBoost

            // Binds to the service so new EQ levels can be passed in when the user sets them, updates app state
            myContext.bindService(foregroundServiceIntent, connection, BIND_AUTO_CREATE)
            isBound = true
            onEqEnabled()
        }
    }

    fun startMediaListenService(
        activity: Activity,
        eqLevels: MutableList<Float>,
        globalAudio: Boolean,
        bassBoost: Short = 0
    ): Boolean {
        // Checking for and requesting notification permission if not already given
        val permissionGranted = checkNotificationPermission(activity)

        if (!permissionGranted) {
            return false
        }

        latestEqLevels = eqLevels
        latestGlobalAudio = globalAudio
        latestBassBoost = bassBoost

        // Starting the foreground service that listens for media streams starting
        myContext.startForegroundService(foregroundServiceIntent)
        // Binds to the service so new EQ levels can be passed in when the user sets them
        myContext.bindService(foregroundServiceIntent, connection, BIND_AUTO_CREATE)
        isBound = true

        return true
    }

    fun stopMediaListenService() {
        // Unbinds from foreground service
        unbindForegroundService()
        // Stops the foreground service that listens for media streams starting
        myContext.stopService(foregroundServiceIntent)
    }

    fun unbindForegroundService() {
        // Only unbind if we actually have an active bindService() call outstanding -
        // calling unbindService() twice (or when never bound) throws IllegalArgumentException
        if (!isBound) {
            return
        }

        try {
            myContext.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Defensive: service connection was already torn down (e.g. by the system
            // killing the service process) - nothing left to unbind.
        } finally {
            isBound = false
            eqService = null
        }
    }

    private fun checkNotificationPermission(activity: Activity): Boolean {
        // Function to check whether notification permission is given, and request it if not
        // Below Android 13, permission is automatically granted for notifications
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        // Checking whether notifications are enabled
        var notificationPermission = ActivityCompat.checkSelfPermission(
            myContext,
            Manifest.permission.POST_NOTIFICATIONS
        )

        // Requesting permission if not granted
        if (notificationPermission == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                0
            )

            notificationPermission = ContextCompat.checkSelfPermission(
                myContext,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        // If permission granted, return true
        return notificationPermission == PackageManager.PERMISSION_GRANTED
    }
}
