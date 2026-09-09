package com.turbofan3360.openeq.audioprocessing

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.min
import kotlin.math.round

private const val DECIBEL_TO_MILLIBEL = 100f
private const val HERZ_TO_MILLIHERZ = 1000f
private const val ONE_MEGAHERZ = 1000 // in Hz

// Some devices' audio effects framework reserves headroom (roughly -3dB, i.e. ~30% of
// perceived volume) as soon as an Equalizer effect is instantiated on a session/mix -
// even if every band is left at 0dB. A LoudnessEnhancer with a fixed makeup gain applied
// alongside the Equalizer compensates for that loss so enabling the EQ doesn't quietly
// turn the volume down. See EqForegroundService for where this is used.
const val EQ_MAKEUP_GAIN_MILLIBEL = 300 // +3dB

// A series of utility functions to help the code manage equalizer instances

fun addEqualizer(
    audioSession: Int
): Equalizer {
    // Adds an equalizer session to a given audio stream and returns the EQ object
    val eqObject = Equalizer(0, audioSession)
    eqObject.setEnabled(true)

    return eqObject
}

fun delEqualizer(
    eq: Equalizer
) {
    // Tidies up and deletes an EQ instance
    eq.release()
}

fun setEqualizer(
    eq: Equalizer,
    levels: List<Float>
) {
    // Working out the maximum number of times I can loop and set an EQ level
    // Can't be more than the number of bands, but must be equal to the number of levels passed
    val loopLim = min(levels.size, eq.numberOfBands.toInt())

    // Sets levels of an equalizer instance to the given values
    for (i in 0..<loopLim) {
        eq.setBandLevel(i.toShort(), round(levels[i] * DECIBEL_TO_MILLIBEL).toInt().toShort())
    }
}

fun getEqBands(context: Context): List<Float> {
    // Returns a list of the center frequencies of the EQ bands available in Hz
    val eqObj = addEqualizer(getAudioSessionId(context))
    val frequencies = mutableListOf<Float>()

    for (i in 0..<eqObj.numberOfBands) {
        frequencies += eqObj.getCenterFreq(i.toShort()) / HERZ_TO_MILLIHERZ
    }

    delEqualizer(eqObj)

    return frequencies.toList()
}

fun getEqRange(context: Context): List<Float> {
    // Returns a [min, max] float value range that the EQ lets you select on this device
    val eqObj = addEqualizer(getAudioSessionId(context))
    val eqRange = eqObj.getBandLevelRange()

    delEqualizer(eqObj)

    return listOf(eqRange[0] / DECIBEL_TO_MILLIBEL, eqRange[1] / DECIBEL_TO_MILLIBEL)
}

fun eqFrequenciesToLabels(
    frequencyBands: List<Float>
): List<String> {
    // Takes in the list of frequency bands (in Hz) and generates nice labels for them
    val labels = mutableListOf<String>()
    var baseStr: String

    for (freq in frequencyBands) {
        if (freq < ONE_MEGAHERZ) {
            // For frequencies below 1KHz - can just convert straight to string and add to list
            labels += freq.toString().dropLastWhile { it == '0' }.dropLastWhile { it == '.' }
        } else {
            // Generates nice "1K"/"2K"/e.t.c labels for frequencies above 1KHz
            baseStr = (freq / HERZ_TO_MILLIHERZ).toString().dropLastWhile { it == '0' }.dropLastWhile { it == '.' }
            labels += "${baseStr}K"
        }
    }

    return labels.toList()
}

// ---------------------------------------------------------
// Loudness compensation - fixes the volume drop caused by
// attaching an Equalizer effect to a session (see comment above)
// ---------------------------------------------------------

fun addLoudnessCompensation(audioSession: Int): LoudnessEnhancer {
    // Adds a makeup-gain LoudnessEnhancer to a session to counteract the headroom the
    // Equalizer effect reserves on that session
    val loudnessObject = LoudnessEnhancer(audioSession)
    loudnessObject.setTargetGain(EQ_MAKEUP_GAIN_MILLIBEL)
    loudnessObject.setEnabled(true)

    return loudnessObject
}

fun delLoudnessCompensation(loudness: LoudnessEnhancer) {
    loudness.release()
}

// ---------------------------------------------------------
// Bass boost - ported from ArchiveTune's equalizer module
// (moe.rukamori.archivetune.playback.MusicService), which wraps
// the platform's android.media.audiofx.BassBoost effect.
// Strength is 0-1000 as defined by the Android AudioEffect API.
// ---------------------------------------------------------

const val BASS_BOOST_MIN_STRENGTH: Short = 0
const val BASS_BOOST_MAX_STRENGTH: Short = 1000

fun addBassBoost(audioSession: Int): BassBoost {
    // Adds a bass boost effect to the given audio session
    val bassBoostObject = BassBoost(0, audioSession)
    bassBoostObject.setEnabled(false)

    return bassBoostObject
}

fun delBassBoost(bassBoost: BassBoost) {
    bassBoost.release()
}

fun setBassBoost(bassBoost: BassBoost, strength: Short) {
    // Strength of 0 effectively means "off", to match the behaviour of the EQ bands
    val clampedStrength = strength.coerceIn(BASS_BOOST_MIN_STRENGTH, BASS_BOOST_MAX_STRENGTH)

    bassBoost.setEnabled(clampedStrength > BASS_BOOST_MIN_STRENGTH)

    if (bassBoost.strengthSupported) {
        bassBoost.setStrength(clampedStrength)
    }
}

fun globalEqAllowed(): Boolean {
    // Function that checks to see if an equalizer object attached to global session (id=0) is allowed by the device
    try {
        val eqObj = addEqualizer(0)

        delEqualizer(eqObj)
        return true
    } catch (_: RuntimeException) {
        return false
    }
}

private fun getAudioSessionId(context: Context): Int {
    // Generates a valid audio session ID from the system and passes it back
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val sessionId = audioManager.generateAudioSessionId()

    return sessionId
}
