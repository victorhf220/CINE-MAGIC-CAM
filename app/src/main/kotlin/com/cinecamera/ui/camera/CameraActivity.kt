package com.cinecamera.ui.camera

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cinecamera.R
import com.cinecamera.audio.VUMeterData
import com.cinecamera.camera.AfState
import com.cinecamera.databinding.ActivityCameraBinding
import com.cinecamera.stability.ThermalAlert
import com.cinecamera.streaming.RTMPState
import com.cinecamera.streaming.StreamingState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * CameraActivity
 *
 * Primary recording activity — landscape-only, full immersive mode.
 * Responsible exclusively for view binding, animation control, and
 * forwarding user interactions to the ViewModel. No business logic lives here.
 *
 * HUD element bindings follow the CSS naming convention from the design spec:
 *   panelLeft    → left manual control labels
 *   panelRight   → assist tool toggles
 *   topBar       → status metadata strip
 *   bottomBar    → record button, VU meters, REC indicator
 */
@AndroidEntryPoint
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val viewModel: CameraViewModel by viewModels()

    private val blinkAnimation by lazy {
        AnimationUtils.loadAnimation(this, R.anim.rec_blink)
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersiveFullscreen()

        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPreviewSurface()
        setupInteractiveControls()
        observeViewModel()

        viewModel.initializeCamera()
    }

    override fun onResume() {
        super.onResume()
        configureImmersiveFullscreen()  // Re-apply after system UI re-appears
    }

    override fun onPause() {
        super.onPause()
        // Stop recording if activity loses focus mid-take
        if (viewModel.uiState.value.isRecording) {
            viewModel.stopRecording()
        }
    }

    // ─── Display Configuration ─────────────────────────────────────────────────

    /**
     * Configures true immersive mode: status bar and navigation bar are hidden,
     * reappearing only on swipe from screen edge. Screen is kept on at all times.
     * Matches the CSS `overflow: hidden` and full-viewport intent.
     */
    private fun configureImmersiveFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ─── Preview Surface ───────────────────────────────────────────────────────

    private fun setupPreviewSurface() {
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                viewModel.startPreview(holder.surface)
                Timber.d("Preview surface created")
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    // ─── Interactive Controls ──────────────────────────────────────────────────

    /**
     * Wires all interactive HUD elements. Per the CSS spec, the HUD overlay
     * itself has pointer-events: none; only specific children are interactive.
     * In Android, the ConstraintLayout root has clickable=false; individual
     * Views that need interaction are explicitly clickable=true in the layout.
     */
    private fun setupInteractiveControls() {

        // ── Record button ──────────────────────────────────────────────────────
        // CSS: .record-btn — the primary interactive element of the bottom bar
        binding.btnRecord.setOnClickListener {
            val isRecording = viewModel.uiState.value.isRecording
            if (isRecording) viewModel.stopRecording() else viewModel.startRecording()
        }

        // ── Assist tool toggles (tap on right-panel labels) ────────────────────
        // CSS: .tool / .tool.on state toggle
        binding.toolZebra.apply {
            isClickable = true
            setOnClickListener { toggleTool(this) { viewModel.setZebra(!it) } }
        }
        binding.toolPeaking.apply {
            isClickable = true
            setOnClickListener { toggleTool(this) { viewModel.setFocusPeaking(!it) } }
        }
        binding.toolWaveform.apply {
            isClickable = true
            setOnClickListener { toggleTool(this) { viewModel.setWaveform(!it) } }
        }
        binding.toolHistogram.apply {
            isClickable = true
            setOnClickListener { toggleTool(this) { /* histogram toggle */ } }
        }

        // ── Parameter row taps — mark row as .active ───────────────────────────
        // CSS: .param.active — font-weight 600, opacity 1.0
        val paramRows = listOf(
            binding.paramISORow,
            binding.paramShutterRow,
            binding.paramWBRow,
            binding.paramFocusRow,
            binding.paramFPSRow
        )
        paramRows.forEach { row ->
            row.isClickable = true
            row.setOnClickListener { selectParamRow(it, paramRows) }
        }
    }

    /**
     * Toggles a tool indicator between the off and on visual states.
     * CSS: .tool → .tool.on (color: #ffffff, opacity: 1)
     * Android: style change simulated via setTextAppearance.
     */
    private fun toggleTool(view: android.widget.TextView, onToggle: (Boolean) -> Unit) {
        val isNowOn = view.tag != true
        view.tag = isNowOn
        if (isNowOn) {
            view.setTextAppearance(R.style.HUD_Tool_On)
        } else {
            view.setTextAppearance(R.style.HUD_Tool)
        }
        onToggle(isNowOn)
    }

    /**
     * Applies .param.active to the tapped row and removes it from all others.
     * CSS: .param.active — opacity: 1, font-weight: 600
     */
    private fun selectParamRow(selected: View, all: List<View>) {
        all.forEach { row ->
            val label = (row as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.TextView
            val value = (row as? android.view.ViewGroup)?.getChildAt(1) as? android.widget.TextView
            val isActive = row == selected
            label?.setTextAppearance(if (isActive) R.style.HUD_Param_Active else R.style.HUD_Param)
            value?.setTextAppearance(R.style.HUD_Param_Value)
        }
    }

    // ─── ViewModel Observation ─────────────────────────────────────────────────

    private fun observeViewModel() {
        observeUIState()
        observeCaptureMetrics()
        observeEncodingMetrics()
        observeAudioMetrics()
        observeStreamingState()
        observeThermalAlerts()
    }

    private fun observeUIState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                // Record button state → drawable selector via isActivated
                // CSS: .record-btn / .record-btn.recording
                binding.btnRecord.isActivated = state.isRecording

                // REC indicator blink animation
                // CSS: .rec-indicator + @keyframes blink
                if (state.isRecording) {
                    binding.recIndicator.visibility = View.VISIBLE
                    binding.recIndicator.startAnimation(blinkAnimation)
                } else {
                    binding.recIndicator.clearAnimation()
                    binding.recIndicator.visibility = View.INVISIBLE
                }

                // Record button glow effect when recording
                // CSS: box-shadow: 0 0 15px rgba(255,0,0,0.6)
                binding.btnRecord.elevation = if (state.isRecording) 12f else 0f
            }
        }
    }

    private fun observeCaptureMetrics() {
        lifecycleScope.launch {
            viewModel.captureMetrics.collectLatest { metrics ->
                // CSS: .param.active values updated in real time
                binding.valueISO.text = "%.0f".format(metrics.iso.toFloat())
                binding.valueShutter.text = formatShutter(metrics.shutterSpeedNs)
                binding.valueWB.text = "${metrics.whiteBalanceKelvin}K"
                binding.valueFocus.text = if (metrics.afState == AfState.LOCKED) "MF" else "AF"
                binding.valueFPS.text = viewModel.uiState.value.selectedFps.toString()
            }
        }
    }

    private fun observeEncodingMetrics() {
        lifecycleScope.launch {
            viewModel.encodingMetrics.collectLatest { metrics ->
                // Top bar: bitrate and duration
                // CSS: .top-bar items
                binding.hudBitrate.text = "${metrics.actualBitrateKbps / 1000} Mbps"
                binding.hudDuration.text = formatDuration(metrics.recordingDurationMs)

                // Dropped frames alert
                // CSS: .error state
                if (metrics.droppedFrames > 3) {
                    binding.hudDroppedFrames.text = "DROP ${metrics.droppedFrames}"
                    binding.hudDroppedFrames.visibility = View.VISIBLE
                } else {
                    binding.hudDroppedFrames.visibility = View.GONE
                }
            }
        }
    }

    private fun observeAudioMetrics() {
        // VU meter fill widths driven by RMS level
        // CSS: .audio-level { width: 60% } — dynamic equivalent
        lifecycleScope.launch {
            viewModel.vuMeterData.collectLatest { data ->
                updateAudioMeter(binding.audioMeterLeft, data.leftRMS)
                updateAudioMeter(binding.audioMeterRight, data.rightRMS)
            }
        }

        // Clipping indicator
        // CSS: .error
        lifecycleScope.launch {
            viewModel.clippingAlert.collectLatest { clipping ->
                binding.audioClipWarning.visibility = if (clipping) View.VISIBLE else View.GONE
            }
        }
    }

    private fun observeStreamingState() {
        lifecycleScope.launch {
            viewModel.srtState.collectLatest { state ->
                when (state) {
                    is StreamingState.Live -> {
                        binding.liveIndicator.visibility = View.VISIBLE
                        binding.liveIndicator.startAnimation(blinkAnimation)
                        binding.hudNetwork.visibility = View.VISIBLE
                    }
                    is StreamingState.Reconnecting -> {
                        binding.hudNetwork.text = getString(R.string.broadcast_reconnecting)
                    }
                    else -> {
                        binding.liveIndicator.clearAnimation()
                        binding.liveIndicator.visibility = View.GONE
                        binding.hudNetwork.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.srtStats.collectLatest { stats ->
                if (viewModel.uiState.value.isBroadcasting) {
                    binding.hudNetwork.text =
                        "RTT ${stats.rttMs.toInt()}ms  LOSS ${"%.1f".format(stats.packetLossPct)}%"
                }
            }
        }

        lifecycleScope.launch {
            viewModel.rtmpState.collectLatest { state ->
                when (state) {
                    is RTMPState.Live -> {
                        binding.liveIndicator.visibility = View.VISIBLE
                        binding.liveIndicator.startAnimation(blinkAnimation)
                        binding.hudNetwork.visibility = View.VISIBLE
                        binding.hudNetwork.text = "RTMP  ${state.host}"
                    }
                    is RTMPState.Reconnecting -> {
                        binding.hudNetwork.text = getString(R.string.broadcast_reconnecting)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeThermalAlerts() {
        // CSS: .warning / .error — thermal states
        lifecycleScope.launch {
            viewModel.thermalAlert.collectLatest { alert ->
                when (alert) {
                    is ThermalAlert.Warning -> {
                        binding.hudThermal.apply {
                            text = "${alert.tempC.toInt()}°C"
                            setTextAppearance(R.style.HUD_Alert_Warning)
                            visibility = View.VISIBLE
                        }
                    }
                    is ThermalAlert.Critical, is ThermalAlert.Emergency -> {
                        val temp = (alert as? ThermalAlert.Critical)?.tempC
                            ?: (alert as ThermalAlert.Emergency).tempC
                        binding.hudThermal.apply {
                            text = "HOT ${temp.toInt()}°C"
                            setTextAppearance(R.style.HUD_Alert_Error)
                            visibility = View.VISIBLE
                        }
                    }
                    ThermalAlert.Normal -> {
                        binding.hudThermal.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps the VU meter RMS dBFS value (-∞ to 0) to a pixel width
     * and applies it as the fill View's width constraint ratio.
     * CSS equivalent: `.audio-level { width: <pct>% }`
     */
    private fun updateAudioMeter(fillView: View, rmsDb: Float) {
        val parent = fillView.parent as? android.widget.FrameLayout ?: return
        val pct = when {
            rmsDb <= -60f -> 0f
            rmsDb >= 0f   -> 1f
            else          -> (rmsDb + 60f) / 60f   // normalize -60..0 → 0..1
        }
        val targetWidth = (parent.width * pct).toInt()
        val params = fillView.layoutParams
        params.width = targetWidth
        fillView.layoutParams = params
    }

    private fun formatShutter(ns: Long): String {
        if (ns <= 0) return "1/60"
        val denominator = (1_000_000_000.0 / ns).toInt()
        return "1/$denominator"
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
