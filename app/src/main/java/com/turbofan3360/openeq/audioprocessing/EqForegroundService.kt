package com.turbofan3360.openeq.audioprocessing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.turbofan3360.openeq.MainActivity
import com.turbofan3360.openeq.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PERMANENT_NOTIFICATION_ID = 1
private const val NOTIFICATION_CHANNEL_ID = "eq_service_channel"
// How long to wait after the last slider movement before actually writing to the
// AudioEffect objects. Each setBandLevel() call is a blocking cross-process Binder
// call into audioserver - without this, dragging a slider fires dozens of those
// per second and jank follows since they were previously run on the calling (UI) thread.
private const val EQ_APPLY_DEBOUNCE_MS = 40L

// Foreground service that listens for media streams starting and then attaches equalizers to them
class EqForegroundService : Service() {
    // Initializes on first access to variable
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val mediaStreamStartListener = MediaStreamStartReceiver()
    private val mediaStreamStopListener = MediaStreamStopReceiver()
    private var eqObjects = mutableMapOf<Int, Equalizer>()
    // Bass boost effect, attached alongside every Equalizer
    private var bassBoostObjects = mutableMapOf<Int, BassBoost>()
    private var eqLevels = mutableListOf<Float>()
    private var bassBoostStrength: Short = 0
    private var tryGlobalMix = false
    private var binder = LocalBinder()

    // Background scope + debounce job for applying AudioEffect changes off the main thread
    private val effectsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingEqApplyJob: Job? = null
    private var pendingBassBoostApplyJob: Job? = null

    // ---------------------------------
    // Handles binding to this service
    // ---------------------------------
    inner class LocalBinder : Binder() {
        fun getService() = this@EqForegroundService
    }

    override fun onBind(intent: Intent): IBinder {
        // Returns a binder object to interact with this service
        return binder
    }
    // ---------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called when starting the service
        // Calling the function that handles creating the notification
        eqNotification()

        // Registering the broadcast receiver to listen for media streams starting
        ContextCompat.registerReceiver(
            this,
            mediaStreamStartListener,
            IntentFilter("android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION"),
            ContextCompat.RECEIVER_EXPORTED
        )
        // Registering the broadcast receiver to listen for media streams stopping
        ContextCompat.registerReceiver(
            this,
            mediaStreamStopListener,
            IntentFilter("android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION"),
            ContextCompat.RECEIVER_EXPORTED
        )

        isRunning = true

        return START_STICKY
    }

    override fun onDestroy() {
        // Tidies up everything when stopping the foreground service
        // Cancelling any pending debounced effect writes and the scope that runs them
        effectsScope.cancel()

        // Deletes notification
        NotificationManagerCompat.from(this).cancel(PERMANENT_NOTIFICATION_ID)
        // Unregistering the broadcast receivers
        this.unregisterReceiver(mediaStreamStartListener)
        this.unregisterReceiver(mediaStreamStopListener)
        // Releasing all EQ and bass boost objects
        for ((_, eqObj) in eqObjects) {
            delEqualizer(eqObj)
        }
        eqObjects.clear()

        for ((_, bassBoostObj) in bassBoostObjects) {
            delBassBoost(bassBoostObj)
        }
        bassBoostObjects.clear()

        isRunning = false
    }

    // Public function that lets you update the equalizer levels
    // Intended to be called from MainActivity when that is bound to this service
    fun updateEqLevels(
        newEqLevels: MutableList<Float>
    ) {
        eqLevels = newEqLevels

        // Cancelling any not-yet-applied update so a fast drag only results in one
        // (the latest) write to the AudioEffect objects, instead of one per pixel moved
        pendingEqApplyJob?.cancel()
        pendingEqApplyJob = effectsScope.launch {
            delay(EQ_APPLY_DEBOUNCE_MS)

            // Setting all EQ instances to the new levels - off the main thread, since
            // each setBandLevel() call is a blocking Binder call into audioserver
            for ((_, eqObj) in eqObjects) {
                setEqualizer(eqObj, eqLevels)
            }
        }
    }

    // Public function that lets you update the bass boost strength (0-1000)
    // Intended to be called from MainActivity when that is bound to this service
    fun updateBassBoost(newStrength: Short) {
        bassBoostStrength = newStrength

        pendingBassBoostApplyJob?.cancel()
        pendingBassBoostApplyJob = effectsScope.launch {
            delay(EQ_APPLY_DEBOUNCE_MS)

            // Applying the new strength to every currently-attached bass boost instance
            for ((_, bassBoostObj) in bassBoostObjects) {
                setBassBoost(bassBoostObj, bassBoostStrength)
            }
        }
    }

    // Public function that lets you update whether or not the EQ tries to use the global mix
    fun updateTryGlobalAudio(value: Boolean) {
        tryGlobalMix = value

        // If the user has enabled global mix EQ, then create a global EQ instance and clear all the others
        if (tryGlobalMix) {
            // Releasing all existing per-session effect objects
            for ((_, eqObj) in eqObjects) {
                delEqualizer(eqObj)
            }
            eqObjects.clear()
            for ((_, bassBoostObj) in bassBoostObjects) {
                delBassBoost(bassBoostObj)
            }
            bassBoostObjects.clear()

            // Attaching effects to the global mix session (id=0)
            attachEffectsToSession(0)
        }

        // If global mix disabled AND global mix effect objects exist - release & remove them
        else if (eqObjects.containsKey(0)) {
            delEqualizer(eqObjects.remove(0)!!)
            bassBoostObjects.remove(0)?.let { delBassBoost(it) }
        }
    }

    // Attaches an Equalizer and bass boost to the given session, and applies the
    // current EQ levels / bass boost strength to them
    private fun attachEffectsToSession(sessionId: Int) {
        val eqObj = addEqualizer(sessionId)
        setEqualizer(eqObj, eqLevels)
        eqObjects[sessionId] = eqObj

        val bassBoostObj = addBassBoost(sessionId)
        setBassBoost(bassBoostObj, bassBoostStrength)
        bassBoostObjects[sessionId] = bassBoostObj
    }

    private fun eqNotification() {
        // Creating a notification channel to post my notification to
        createEqNotificationChannel(
            getString(R.string.notification_channel_name),
            getString(R.string.notification_channel_info),
        )

        // Creating the intent to happen when notification is tapped
        val tapIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Creating the notification object for my foreground service notification
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_info))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Shows notification on notification channel
        startForeground(PERMANENT_NOTIFICATION_ID, notification)
    }

    private fun createEqNotificationChannel(
        channelName: String,
        channelDescription: String,
    ) {
        // Checking if it already exists (if so, don't re-create it)
        val existingChannel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)

        if (existingChannel == null) {
            // Creates a notification channel that notifications can then be posted to
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                importance
            ).apply {
                description = channelDescription
            }
            // Register the channel with the system
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        var isRunning = false
    }

    // ---------------------
    //  BROADCAST RECEIVERS
    // ---------------------

    inner class MediaStreamStartReceiver : BroadcastReceiver() {
        // Defining what happens when it detects a media stream starting
        override fun onReceive(context: Context?, intent: Intent?) {
            // If user wants to attach to the global mix - ignore all this
            if (tryGlobalMix) {
                return
            }

            // Getting audio stream ID
            val mediaStreamID = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)

            // If the ID is valid, creates an equalizer object attached to that stream
            // Saves the equalizer object to the map
            // Then sets the equalizer levels on that EQ object to the current levels
            if (mediaStreamID != null && mediaStreamID != 0 && !eqObjects.containsKey(mediaStreamID)) {
                attachEffectsToSession(mediaStreamID)
            }
        }
    }

    inner class MediaStreamStopReceiver : BroadcastReceiver() {
        // Defining what happens when it detects a media stream ending
        override fun onReceive(context: Context?, intent: Intent?) {
            // If user wants to attach to the global mix - ignore all this
            if (tryGlobalMix) {
                return
            }

            // Getting audio stream ID
            val mediaStreamID = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)

            // If the ID is valid:
            // Gets the EQ object attached to the given media stream, closes the EQ, and removes it from the map
            if (mediaStreamID != null && mediaStreamID != 0) {
                eqObjects.remove(mediaStreamID)?.let { delEqualizer(it) }
                bassBoostObjects.remove(mediaStreamID)?.let { delBassBoost(it) }
            }
        }
    }
}
