package com.rider.log

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** 음성 안내(한국어 여성 목소리)와 '띠링' 효과음 */
class Alerter(ctx: Context) {

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusReq: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()

    init {
        tts = TextToSpeech(app) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                t.language = Locale.KOREAN
                t.setAudioAttributes(attrs)
                pickFemaleVoice(t)
                ready = true
                pendingText?.let { speak(it) }
                pendingText = null
            }
        }
    }

    /** 한국어 음성 중 여성 목소리를 우선 선택 (삼성 TTS: SMTf, 구글 TTS: ism 등) */
    private fun pickFemaleVoice(t: TextToSpeech) {
        try {
            val ko = (t.voices ?: return).filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired }
            val female = ko.firstOrNull { v ->
                val n = v.name.lowercase()
                n.contains("female") || n.contains("smtf") || n.contains("-ism") || n.contains("kob") ||
                    (v.features?.any { it.lowercase().contains("female") } == true)
            }
            (female ?: ko.firstOrNull())?.let { t.voice = it }
        } catch (e: Exception) {
        }
    }

    fun speak(text: String) {
        if (!ready) {
            pendingText = text
            return
        }
        audio.requestAudioFocus(focusReq)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rl-" + System.currentTimeMillis())
        main.postDelayed({ audio.abandonAudioFocusRequest(focusReq) }, 4500)
    }

    /** '띠링' 두 음 효과음 */
    fun chime() {
        Thread {
            try {
                val sr = 44100
                val notes = listOf(1318.5 to 0.11, 1760.0 to 0.32)
                val total = notes.sumOf { (sr * it.second).toInt() }
                val buf = ShortArray(total)
                var i = 0
                for ((f, dur) in notes) {
                    val n = (sr * dur).toInt()
                    for (k in 0 until n) {
                        val t = k.toDouble() / sr
                        val env = exp(-t * 8.0) * min(1.0, k / (sr * 0.004))
                        val v = sin(2 * PI * f * t) * 0.6 + sin(4 * PI * f * t) * 0.12
                        buf[i++] = (v * env * 0.55 * 32767).toInt().toShort()
                    }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                audio.requestAudioFocus(focusReq)
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(700)
                track.release()
                audio.abandonAudioFocusRequest(focusReq)
            } catch (e: Exception) {
            }
        }.start()
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        ready = false
    }
}
