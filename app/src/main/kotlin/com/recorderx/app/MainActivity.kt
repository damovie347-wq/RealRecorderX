package com.recorderx.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.recorderx.app.bitrate.BitrateAdvisor
import com.recorderx.app.codec.CodecSelector
import com.recorderx.app.service.RecordingService
import com.recorderx.app.service.RecordingSessionState
import com.recorderx.app.settings.AudioChannelMode
import com.recorderx.app.settings.AudioMonitoringMode
import com.recorderx.app.settings.AudioQualityOption
import com.recorderx.app.settings.AudioSourceOption
import com.recorderx.app.settings.BitrateMode
import com.recorderx.app.settings.BitrateOption
import com.recorderx.app.settings.FrameRateOption
import com.recorderx.app.settings.MicGainMode
import com.recorderx.app.settings.OrientationOption
import com.recorderx.app.settings.RecordingSettings
import com.recorderx.app.settings.ResolutionOption
import com.recorderx.app.settings.SettingsRepository
import com.recorderx.app.settings.ThemeMode
import com.recorderx.app.settings.ThemePreference
import com.recorderx.app.settings.VideoCodecOption
import com.recorderx.app.settings.VoicePriority
import com.recorderx.app.ui.SegmentedSliderView
import com.recorderx.app.util.DeviceTier
import com.recorderx.app.util.PermissionManager
import com.recorderx.app.util.ResolutionResolver
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var settings: RecordingSettings
    private lateinit var themePreference: ThemePreference

    private lateinit var settingsContainer: LinearLayout
    private lateinit var btnStartRecording: MaterialButton
    private lateinit var bitrateSuggestionLabel: TextView
    private lateinit var bitrateSlider: SegmentedSliderView
    private lateinit var bitrateLabelsRow: LinearLayout
    private lateinit var resolutionUpscaleLabel: TextView
    private var advancedBitrate = false

    private val sessionListener: () -> Unit = { refreshStartButton() }

    // ---- Activity Result launchers (must be registered before STARTED) ----

    private val runtimePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[android.Manifest.permission.RECORD_AUDIO] == false) {
                toast(R.string.toast_mic_denied)
            }
            proceedToOverlayCheck()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Whatever happened in Settings, the bubble is optional -- keep going.
            proceedToProjectionConsent()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                launchRecordingService(result.resultCode, data)
            } else {
                toast("Screen capture permission is required to record.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        settings = settingsRepository.load()
        themePreference = ThemePreference(this)
        advancedBitrate = settings.advancedBitrateUnlocked

        setContentView(buildRootView())
        refreshStartButton()
    }

    override fun onStart() {
        super.onStart()
        RecordingSessionState.addListener(sessionListener)
        refreshStartButton()
    }

    override fun onStop() {
        RecordingSessionState.removeListener(sessionListener)
        super.onStop()
    }

    // ---- UI construction ---------------------------------------------

    private fun buildRootView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.background))
        }

        if (themePreference.load() == ThemeMode.AMOLED) {
            // Dark mode's resource-driven palette already applied via
            // AppCompatDelegate's night mode (see App#onCreate); AMOLED layers
            // true black on top of it for the main surfaces and system bars,
            // which is what actually saves power / looks right on an OLED panel.
            root.setBackgroundColor(Color.BLACK)
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }

        root.addView(buildTopBar())

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        settingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(8), pad, dp(24))
        }
        scroll.addView(settingsContainer)
        root.addView(scroll)
        populateSettings(settingsContainer)

        root.addView(buildStartButton())
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBlock.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_yellow))
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.02f
        })
        titleBlock.addView(TextView(this).apply {
            text = "${getString(R.string.saved_in_prefix)} /Movies/RecorderX"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
        })
        bar.addView(titleBlock)

        bar.addView(smallNavButton(getString(R.string.nav_open_last)) { openLastRecording() })
        bar.addView(smallNavButton(getString(R.string.nav_guide)) { showGuideDialog() })
        bar.addView(smallNavButton(getString(R.string.nav_theme)) { showThemeDialog() })

        return bar
    }

    private fun smallNavButton(label: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun buildStartButton(): View =
        MaterialButton(this).apply {
            id = View.generateViewId()
            btnStartRecording = this
            text = getString(R.string.start_recording)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.03f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_on_accent))
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.accent_yellow)
            cornerRadius = dp(0) // pill radius set below once height is known via post()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.cta_height)
            ).apply {
                val m = dp(20)
                setMargins(m, dp(10), m, dp(20))
            }
            elevation = 0f
            stateListAnimator = null
            post { cornerRadius = height / 2 }
            setOnClickListener { onStartStopClicked() }
        }

    private fun populateSettings(container: LinearLayout) {
        addFloatingBubbleToggle(container)

        addSectionHeader(container, getString(R.string.section_video))
        addEnumSlider(container, getString(R.string.label_video_codec), VideoCodecOption.values(), settings.videoCodec, { it.label }) {
            updateSettings { copy(videoCodec = it) }
            refreshBitrateSuggestion()
        }
        addEnumSlider(container, getString(R.string.label_orientation), OrientationOption.values(), settings.orientation, { it.label }) {
            updateSettings { copy(orientation = it) }
        }
        addResolutionBlock(container)
        addEnumSlider(container, getString(R.string.label_frame_rate), FrameRateOption.values(), settings.frameRate, { it.label }) {
            updateSettings { copy(frameRate = it) }
            refreshBitrateSuggestion()
        }
        addBitrateBlock(container)
        addEnumSlider(container, getString(R.string.label_bitrate_mode), BitrateMode.values(), settings.bitrateMode, { it.label }) {
            updateSettings { copy(bitrateMode = it) }
        }

        addSectionHeader(container, getString(R.string.section_audio))
        addEnumSlider(container, getString(R.string.label_audio_source), AudioSourceOption.values(), settings.audioSource, { it.label }) {
            updateSettings { copy(audioSource = it) }
            refreshBitrateSuggestion()
        }
        addEnumSlider(container, getString(R.string.label_audio_quality), AudioQualityOption.values(), settings.audioQuality, { it.label }) {
            updateSettings { copy(audioQuality = it) }
        }
        addEnumSlider(container, getString(R.string.label_audio_channel), AudioChannelMode.values(), settings.audioChannel, { it.label }) {
            updateSettings { copy(audioChannel = it) }
        }
        addEnumSlider(container, getString(R.string.label_mic_gain), MicGainMode.values(), settings.micGain, { it.label }) {
            updateSettings { copy(micGain = it) }
        }
        addEnumSlider(container, getString(R.string.label_voice_priority), VoicePriority.values(), settings.voicePriority, { it.label }) {
            updateSettings { copy(voicePriority = it) }
        }
        addPercentSlider(
            container,
            titleFormat = R.string.label_system_level,
            initialPercent = settings.systemLevelPercent
        ) { updateSettings { copy(systemLevelPercent = it) } }
        addPercentSlider(
            container,
            titleFormat = R.string.label_mic_level,
            initialPercent = settings.micLevelPercent
        ) { updateSettings { copy(micLevelPercent = it) } }
        addEnumSlider(container, getString(R.string.label_audio_monitoring), AudioMonitoringMode.values(), settings.audioMonitoring, { it.label }) {
            updateSettings { copy(audioMonitoring = it) }
        }

        addOutputTemplateBlock(container)
    }

    private fun addFloatingBubbleToggle(container: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.label_floating_bubble)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(SwitchCompat(this).apply {
            isChecked = settings.floatingBubbleEnabled
            thumbTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.accent_yellow)
            setOnCheckedChangeListener { _, checked -> updateSettings { copy(floatingBubbleEnabled = checked) } }
        })
        container.addView(row)
    }

    private fun addSectionHeader(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_yellow))
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding(0, dp(26), 0, dp(2))
        })
    }

    private fun <T> addEnumSlider(
        container: LinearLayout,
        title: String,
        values: Array<T>,
        current: T,
        labelOf: (T) -> String,
        onSelect: (T) -> Unit
    ) {
        val startIndex = values.indexOf(current).coerceAtLeast(0)
        addSliderBlock(container, title, values.map(labelOf), startIndex) { idx ->
            onSelect(values[idx])
        }
    }

    /** Returns the slider so callers (Bitrate) that need to rebuild it later can keep a reference. */
    private fun addSliderBlock(
        container: LinearLayout,
        title: String,
        stepLabels: List<String>,
        initialIndex: Int,
        onChange: (Int) -> Unit
    ): Triple<SegmentedSliderView, LinearLayout, TextView> {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        block.addView(titleView)

        val slider = SegmentedSliderView(this).apply {
            steps = stepLabels.size.coerceAtLeast(2)
            selectedIndex = initialIndex
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(8)
            }
        }
        block.addView(slider)

        val labelsRow = buildLabelsRow(stepLabels)
        block.addView(labelsRow)

        slider.onIndexChanged = { idx -> onChange(idx) }
        container.addView(block)
        return Triple(slider, labelsRow, titleView)
    }

    private fun buildLabelsRow(labels: List<String>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            labels.forEach { label ->
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

    private fun addResolutionBlock(container: LinearLayout) {
        val options = ResolutionOption.values()
        val startIndex = options.indexOf(settings.resolution).coerceAtLeast(0)
        val (slider, _, _) = addSliderBlock(
            container,
            getString(R.string.label_resolution),
            options.map { it.label },
            startIndex
        ) { idx ->
            updateSettings { copy(resolution = options[idx]) }
            refreshBitrateSuggestion()
            refreshResolutionNote()
        }

        // Wired to ResolutionResolver.isUpscaling, previously computed but
        // never actually shown anywhere -- picking "4K"/"2K" on a panel that
        // doesn't natively hit that resolution silently upscales (still a
        // fully valid, deliberate choice -- MediaProjection can target any
        // size regardless of the physical panel), which reads exactly like
        // "yüksek çözünürlük seçtim ama net görüntü alamıyorum" if the person
        // has no way to know that's what's happening.
        resolutionUpscaleLabel = TextView(this).apply {
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        }
        (slider.parent as LinearLayout).addView(resolutionUpscaleLabel)
        refreshResolutionNote()
    }

    private fun refreshResolutionNote() {
        if (!::resolutionUpscaleLabel.isInitialized) return
        if (ResolutionResolver.isUpscaling(this, settings.resolution)) {
            val panel = DeviceTier.panelResolutionPx(this)
            resolutionUpscaleLabel.text = getString(
                R.string.label_resolution_upscale_note,
                "${panel.x}\u00D7${panel.y}",
                settings.resolution.label
            )
            resolutionUpscaleLabel.visibility = View.VISIBLE
        } else {
            resolutionUpscaleLabel.visibility = View.GONE
        }
    }

    private fun addBitrateBlock(container: LinearLayout) {
        val options = currentBitrateOptions()
        val startIndex = options.indexOf(settings.bitrateOption).coerceAtLeast(0)
        val (slider, labelsRow, _) = addSliderBlock(
            container,
            getString(R.string.label_bitrate),
            options.map { it.label },
            startIndex
        ) { idx ->
            val option = currentBitrateOptions()[idx]
            updateSettings { copy(bitrateOption = option) }
            refreshBitrateSuggestion()
        }
        bitrateSlider = slider
        bitrateLabelsRow = labelsRow

        bitrateSuggestionLabel = TextView(this).apply {
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        }
        (slider.parent as LinearLayout).addView(bitrateSuggestionLabel)

        val advancedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        advancedRow.addView(TextView(this).apply {
            text = getString(R.string.label_advanced_bitrate_toggle)
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        advancedRow.addView(SwitchCompat(this).apply {
            isChecked = advancedBitrate
            thumbTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.accent_yellow)
            setOnCheckedChangeListener { _, checked ->
                advancedBitrate = checked
                updateSettings { copy(advancedBitrateUnlocked = checked) }
                rebuildBitrateSlider()
            }
        })
        (slider.parent as LinearLayout).addView(advancedRow)

        refreshBitrateSuggestion()
    }

    private fun currentBitrateOptions(): List<BitrateOption> {
        val core = listOf(
            BitrateOption.AUTO, BitrateOption.BR_2M, BitrateOption.BR_4M, BitrateOption.BR_8M,
            BitrateOption.BR_12M, BitrateOption.BR_20M, BitrateOption.BR_40M
        )
        val advanced = listOf(BitrateOption.ADV_60M, BitrateOption.ADV_80M, BitrateOption.ADV_100_PLUS)
        return if (advancedBitrate) core + advanced else core
    }

    private fun rebuildBitrateSlider() {
        val options = currentBitrateOptions()
        if (settings.bitrateOption !in options) {
            updateSettings { copy(bitrateOption = BitrateOption.AUTO) }
        }
        bitrateSlider.steps = options.size
        bitrateSlider.selectedIndex = options.indexOf(settings.bitrateOption).coerceAtLeast(0)
        bitrateLabelsRow.removeAllViews()
        options.forEach { option ->
            bitrateLabelsRow.addView(TextView(this).apply {
                text = option.label
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        bitrateSlider.onIndexChanged = { idx ->
            updateSettings { copy(bitrateOption = currentBitrateOptions()[idx]) }
            refreshBitrateSuggestion()
        }
    }

    private fun refreshBitrateSuggestion() {
        if (!::bitrateSuggestionLabel.isInitialized) return
        val target = ResolutionResolver.resolve(this, settings.resolution, settings.orientation)
        val choice = CodecSelector.findBestEncoder(settings.videoCodec, target.width, target.height, settings.frameRate.fps)
        val mime = choice?.mimeType ?: android.media.MediaFormat.MIMETYPE_VIDEO_AVC
        val suggestedBps = BitrateAdvisor.suggestBitrateBps(target.width, target.height, settings.frameRate.fps, mime)
        bitrateSuggestionLabel.text = getString(R.string.label_bitrate_suggested, BitrateAdvisor.formatMbps(suggestedBps))
    }

    private fun addPercentSlider(
        container: LinearLayout,
        titleFormat: Int,
        initialPercent: Int,
        onChange: (Int) -> Unit
    ) {
        val steps = PERCENT_STEP_VALUES
        val startIndex = steps.indexOf(initialPercent.coerceIn(0, 200)).let { if (it >= 0) it else steps.indexOf(100) }

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        val titleView = TextView(this).apply {
            text = getString(titleFormat, steps[startIndex])
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        block.addView(titleView)

        val slider = SegmentedSliderView(this).apply {
            this.steps = steps.size
            selectedIndex = startIndex
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(8)
            }
        }
        block.addView(slider)

        val endsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "0%"
                textSize = 10.5f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "200%"
                textSize = 10.5f
                gravity = Gravity.END
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        block.addView(endsRow)

        slider.onIndexChanged = { idx ->
            val percent = steps[idx]
            titleView.text = getString(titleFormat, percent)
            onChange(percent)
        }

        container.addView(block)
    }

    private fun addOutputTemplateBlock(container: LinearLayout) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(26), 0, 0)
        }

        val input = EditText(this).apply {
            setText(settings.outputTemplate)
            hint = getString(R.string.output_template_hint)
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_yellow))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_output_field)
            textSize = 14f
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    updateSettings { copy(outputTemplate = s?.toString().orEmpty()) }
                }
            })
        }
        block.addView(input)

        block.addView(TextView(this).apply {
            text = getString(R.string.output_template_help)
            textSize = 11.5f
            setPadding(0, dp(10), 0, 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        })

        container.addView(block)
    }

    // ---- Settings persistence ------------------------------------------

    private inline fun updateSettings(mutate: RecordingSettings.() -> RecordingSettings) {
        settings = settings.mutate()
        settingsRepository.save(settings)
    }

    // ---- Start / stop flow ----------------------------------------------

    private fun onStartStopClicked() {
        when (RecordingSessionState.phase) {
            RecordingSessionState.Phase.IDLE -> beginStartFlow()
            RecordingSessionState.Phase.RECORDING, RecordingSessionState.Phase.PAUSED ->
                startService(RecordingService.buildStopIntent(this))
        }
    }

    private fun beginStartFlow() {
        val needed = PermissionManager.requiredRuntimePermissions(this, settings.audioSource.wantsMic)
        if (needed.isEmpty()) {
            proceedToOverlayCheck()
        } else {
            runtimePermissionsLauncher.launch(needed)
        }
    }

    private fun proceedToOverlayCheck() {
        if (settings.floatingBubbleEnabled && !PermissionManager.hasOverlayPermission(this)) {
            toast(R.string.permission_rationale_overlay)
            overlayPermissionLauncher.launch(PermissionManager.overlaySettingsIntent(this))
        } else {
            proceedToProjectionConsent()
        }
    }

    private fun proceedToProjectionConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun launchRecordingService(resultCode: Int, data: Intent) {
        val target = ResolutionResolver.resolve(this, settings.resolution, settings.orientation)
        val density = DeviceTier.screenDensityDpi(this)
        val intent = RecordingService.buildStartIntent(this, resultCode, data, target.width, target.height, density)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refreshStartButton() {
        if (!::btnStartRecording.isInitialized) return
        btnStartRecording.text = when (RecordingSessionState.phase) {
            RecordingSessionState.Phase.IDLE -> getString(R.string.start_recording)
            RecordingSessionState.Phase.RECORDING -> getString(R.string.stop_recording)
            RecordingSessionState.Phase.PAUSED -> getString(R.string.stop_recording)
        }
    }

    // ---- Misc actions -----------------------------------------------------

    private fun openLastRecording() {
        val uriString = settingsRepository.getLastRecordingUri()
        if (uriString == null) {
            toast(R.string.toast_no_last_recording)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("No app found to open this video.")
        }
    }

    private fun showGuideDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_body)
            .setPositiveButton(R.string.guide_close, null)
            .show()
    }

    private fun showThemeDialog() {
        val modes = ThemeMode.values()
        val labels = modes.map { it.label }.toTypedArray()
        val currentIndex = modes.indexOf(themePreference.load()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme_dialog_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                applyThemeMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.guide_close, null)
            .show()
    }

    private fun applyThemeMode(mode: ThemeMode) {
        if (themePreference.load() == mode) return
        themePreference.save(mode)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (mode == ThemeMode.LIGHT) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            } else {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            }
        )
        recreate()
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    private fun toast(resId: Int) = android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private val PERCENT_STEP_VALUES = (0..200 step 10).toList()
    }
}
