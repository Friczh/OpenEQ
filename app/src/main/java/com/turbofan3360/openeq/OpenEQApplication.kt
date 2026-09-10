package com.turbofan3360.openeq

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

private const val CRASH_LOG_FILE_NAME = "crash_log.txt"
private const val LOG_TAG = "OpenEQCrashHandler"

// Custom Application class purely so we can install a global uncaught exception handler.
// This writes crashes to a plain text file in app-external storage so they can be read
// with any file manager, without needing adb/logcat or a PC.
class OpenEQApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashToFile(throwable)
            } catch (writeError: Exception) {
                // If writing the crash log itself fails, at least try to get it into logcat
                Log.e(LOG_TAG, "Failed to write crash log", writeError)
            }

            // Chaining to the default handler preserves normal crash/ANR dialog behaviour
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                exitProcess(1)
            }
        }
    }

    private fun writeCrashToFile(throwable: Throwable) {
        // getExternalFilesDir(null) -> /storage/emulated/0/Android/data/com.turbofan3360.openeq/files
        // Visible to any file manager app without special permissions on Android 9.
        val dir = getExternalFilesDir(null) ?: filesDir
        val logFile = File(dir, CRASH_LOG_FILE_NAME)

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("---- Crash at $timestamp ----\n")
            append(Log.getStackTraceString(throwable))
            append("\n\n")
        }

        // Appending so multiple crashes accumulate rather than overwrite each other
        logFile.appendText(entry)

        Log.e(LOG_TAG, "Crash written to ${logFile.absolutePath}")
    }
}
