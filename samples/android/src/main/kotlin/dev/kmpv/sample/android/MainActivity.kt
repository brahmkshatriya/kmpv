package dev.kmpv.sample.android

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import dev.kmpv.Mpv
import dev.kmpv.MpvAndroidVideoMode
import dev.kmpv.MpvAndroidSurfaceOutput
import dev.kmpv.MpvCommands
import dev.kmpv.MpvEvent
import dev.kmpv.MpvOptions
import dev.kmpv.MpvProperties
import dev.kmpv.MpvProperty
import dev.kmpv.configureAndroidVideoOutput
import dev.kmpv.createAndroidSurfaceOutput
import dev.kmpv.getOrNull
import dev.kmpv.getOrThrow
import dev.kmpv.trackList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val eventScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eventJob: Job? = null
    private var mpv: Mpv? = null
    private var output: MpvAndroidSurfaceOutput? = null
    private var mediaLoaded: Boolean = false
    private val videoMode: MpvAndroidVideoMode
        get() = if (intent.getStringExtra("mode") == "embed") {
            MpvAndroidVideoMode.MediaCodecEmbed
        } else {
            MpvAndroidVideoMode.Gpu
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)

        try {
            Log.i(Tag, "creating player mode=$videoMode")
            val caFile = File(filesDir, "cacert.pem").also { output ->
                assets.open("cacert.pem").use { input ->
                    output.outputStream().use(input::copyTo)
                }
            }
            val client = Mpv.create()
            client.configureAndroidVideoOutput(videoMode).getOrThrow()
            client.setOption(MpvOptions.KeepOpen, true).getOrThrow()
            client.setOption(MpvOptions.Terminal, true).getOrThrow()
            client.setOption("tls-verify", "yes").getOrThrow()
            client.setOption("tls-ca-file", caFile.absolutePath).getOrThrow()
            client.initialize().getOrThrow()
            client.requestLogMessages("debug").getOrThrow()
            eventJob = eventScope.launch {
                client.events.collect { event ->
                    when (event) {
                        is MpvEvent.LogMessage -> Log.d(
                            MpvLogTag,
                            "${event.level}/${event.prefix}: ${event.text.trimEnd()}",
                        )
                        else -> Log.i(MpvEventTag, event.toString())
                    }
                }
            }
            Log.i(Tag, "initialized mpv=${client[MpvProperties.MpvVersion].getOrNull()}")

            mpv = client
            output = client.createAndroidSurfaceOutput(videoMode)
        } catch (failure: Throwable) {
            showError(failure)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val client = mpv ?: return
        val videoOutput = output ?: return
        try {
            Log.i(Tag, "surfaceCreated valid=${holder.surface.isValid} mode=$videoMode")
            videoOutput.attach(holder.surface).getOrThrow()
            Log.i(Tag, "surface attached")
            if (!mediaLoaded) {
                client.command(MpvCommands.loadFile(SampleUrl)).getOrThrow()
                mediaLoaded = true
                Log.i(Tag, "loadfile submitted")
                scheduleSnapshot(client, 2_000, "2s")
                scheduleSnapshot(client, 6_000, "6s")
                scheduleSnapshot(client, 12_000, "12s")
            }
        } catch (failure: Throwable) {
            showError(failure)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            Log.i(Tag, "surfaceChanged ${width}x$height format=$format")
            output?.resize(width, height)?.getOrThrow()
        } catch (failure: Throwable) {
            showError(failure)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            Log.i(Tag, "surfaceDestroyed")
            output?.detach()?.getOrThrow()
            Log.i(Tag, "surface detached")
        } catch (failure: Throwable) {
            showError(failure)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        eventJob?.cancel()
        eventJob = null
        eventScope.cancel()
        surfaceView.holder.removeCallback(this)
        output?.close()
        output = null
        mpv?.close()
        mpv = null
        Log.i(Tag, "destroyed")
        super.onDestroy()
    }

    private fun scheduleSnapshot(client: Mpv, delayMillis: Long, label: String) {
        handler.postDelayed({
            if (client.isClosed) return@postDelayed
            val tracks = client.trackList().getOrNull().orEmpty()
            Log.i(
                Tag,
                buildString {
                    append("snapshot[$label]")
                    append(" path=").append(client[MpvProperties.Path].getOrNull())
                    append(" pos=").append(client[MpvProperties.TimePos].getOrNull())
                    append(" duration=").append(client[MpvProperties.Duration].getOrNull())
                    append(" size=").append(client[MpvProperties.Width].getOrNull())
                    append('x').append(client[MpvProperties.Height].getOrNull())
                    append(" hwdec=").append(client[MpvProperties.HwdecCurrent].getOrNull())
                    append(" vo=").append(client[CurrentVo].getOrNull())
                    append(" voConfigured=").append(client[VoConfigured].getOrNull())
                    append(" tracks=").append(
                        tracks.joinToString { track ->
                            "${track.type}:${track.id}:${track.width}x${track.height}:${track.selected}"
                        },
                    )
                },
            )
        }, delayMillis)
    }

    private fun showError(failure: Throwable) {
        val message = TextView(this).apply {
            gravity = Gravity.CENTER
            text = buildString {
                appendLine("kmpv Android sample")
                appendLine()
                appendLine(failure.message ?: failure::class.simpleName.orEmpty())
                appendLine()
                append("This sample expects an ABI-compatible libmpv.so in the app's native libraries.")
            }
            setPadding(48, 48, 48, 48)
        }
        addContentView(
            message,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private companion object {
        const val Tag: String = "kmpv-android-sample"
        const val MpvLogTag: String = "kmpv-mpv-log"
        const val MpvEventTag: String = "kmpv-mpv-event"
        const val SampleUrl: String = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        val CurrentVo: MpvProperty<String> = MpvProperty.string("current-vo")
        val VoConfigured: MpvProperty<Boolean> = MpvProperty.flag("vo-configured")
    }
}
