package com.example.accessiblealarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager as AndroidAudioManager
import android.os.Build

class AppAudioManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AndroidAudioManager
    private var originalVolume: Int = 0
    private var originalRingerMode: Int = 0
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentVolume: Int = -1

    fun setAppVolume(volume: Int) {
        if (currentVolume == volume) return

        if (currentVolume == -1) {
            originalVolume = audioManager.getStreamVolume(AndroidAudioManager.STREAM_ALARM)
            originalRingerMode = audioManager.ringerMode
        }

        currentVolume = volume

        val maxVolume = audioManager.getStreamMaxVolume(AndroidAudioManager.STREAM_ALARM)
        val targetVolume = (volume * maxVolume) / 100
        
        try {
            // Force ringer mode to normal if it's silent
            if (audioManager.ringerMode == AndroidAudioManager.RINGER_MODE_SILENT) {
                android.util.Log.w("AppAudioManager", "Device in silent mode, forcing to normal for alarm")
                audioManager.ringerMode = AndroidAudioManager.RINGER_MODE_NORMAL
            }
            
            // Set alarm volume
            audioManager.setStreamVolume(
                AndroidAudioManager.STREAM_ALARM,
                targetVolume,
                AndroidAudioManager.FLAG_SHOW_UI
            )
            
            android.util.Log.d("AppAudioManager", "Set alarm volume to $targetVolume/$maxVolume (${volume}%)")
        } catch (e: Exception) {
            android.util.Log.e("AppAudioManager", "Error setting volume", e)
        }
    }

    fun restoreOriginalVolume() {
        if (currentVolume == -1) return

        try {
            audioManager.setStreamVolume(
                AndroidAudioManager.STREAM_ALARM,
                originalVolume,
                0
            )
            audioManager.ringerMode = originalRingerMode
            currentVolume = -1
        } catch (e: Exception) {
            android.util.Log.e("AppAudioManager", "Error restoring volume", e)
        }
    }

    fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AndroidAudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    android.util.Log.d("AppAudioManager", "Audio focus changed: $focusChange")
                    when (focusChange) {
                        AndroidAudioManager.AUDIOFOCUS_GAIN -> {
                            android.util.Log.d("AppAudioManager", "Audio focus gained")
                        }
                        AndroidAudioManager.AUDIOFOCUS_LOSS -> {
                            android.util.Log.w("AppAudioManager", "Audio focus lost")
                        }
                        AndroidAudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            android.util.Log.w("AppAudioManager", "Audio focus lost transient")
                        }
                        AndroidAudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            android.util.Log.w("AppAudioManager", "Audio focus lost transient can duck")
                        }
                    }
                }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            android.util.Log.d("AppAudioManager", "Audio focus request result: $result")
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange ->
                    android.util.Log.d("AppAudioManager", "Audio focus changed (legacy): $focusChange")
                },
                AndroidAudioManager.STREAM_ALARM,
                AndroidAudioManager.AUDIOFOCUS_GAIN
            )
            android.util.Log.d("AppAudioManager", "Audio focus request result (legacy): $result")
        }
    }

    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
} 