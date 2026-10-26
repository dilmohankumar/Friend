package com.situationship.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Incoming ringtone + vibration, and outgoing ringback tone. */
class Ringer(private val ctx: Context) {
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ringbackLoop: Runnable? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun startRingtone() {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
    }

    fun startRingback() {
        stop()
        tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
        val loop = object : Runnable {
            override fun run() {
                tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1500)
                handler.postDelayed(this, 4000)
            }
        }
        ringbackLoop = loop
        handler.post(loop)
    }

    fun stop() {
        ringtone?.stop(); ringtone = null
        ringbackLoop?.let { handler.removeCallbacks(it) }; ringbackLoop = null
        tone?.release(); tone = null
        vibrator?.cancel()
    }
}
