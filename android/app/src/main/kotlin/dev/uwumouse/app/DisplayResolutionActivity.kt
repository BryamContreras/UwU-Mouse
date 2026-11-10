package dev.uwumouse.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.uwumouse.app.runtime.DisplayResolutionController
import java.lang.ref.WeakReference

/** Explicit display controls. Privileged work and session release remain in the controller. */
class DisplayResolutionActivity : Activity() {
    private var targetDisplayId = -1
    private var gameRotation = 0
    private var requestId = 0L
    private var busy = false
    private var initializedInputs = false
    private var snapshot: Bundle? = null
    private lateinit var destination: TextView
    private lateinit var currentResolution: TextView
    private lateinit var physicalResolution: TextView
    private lateinit var stateLabel: TextView
    private lateinit var feedback: TextView
    private lateinit var widthInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var applyButton: Button
    private lateinit var restoreButton: Button
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        gameRotation = intent.getIntExtra(EXTRA_GAME_ROTATION, 0)
        window.setDecorFitsSystemWindows(false)
        buildContent()
        if (savedInstanceState?.containsKey(STATE_WIDTH) == true) {
            widthInput.setText(savedInstanceState.getString(STATE_WIDTH).orEmpty())
            heightInput.setText(savedInstanceState.getString(STATE_HEIGHT).orEmpty())
            initializedInputs = widthInput.text?.isNotBlank() == true || heightInput.text?.isNotBlank() == true
        }
        if (targetDisplayId < 0 || gameRotation !in 0..3) {
            showFeedback("No se pudo identificar la pantalla del juego.", error = true)
            refreshControls()
        } else read()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_WIDTH, widthInput.text.toString())
        outState.putString(STATE_HEIGHT, heightInput.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // An accepted display mutation must finish even if its Activity disappears.
        // Its completion must not update a replaced or destroyed form.
        requestId++
        super.onDestroy()
    }

    private fun buildContent() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 22, 45))
            isFillViewport = true
            addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                WindowInsets.Type.captionBar() or WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        content.addView(button("Volver") { finish() }, rowParams().apply { width = dp(100) })
        content.addView(label("Resolución personalizada", 23f, bold = true), rowParams(16))
        destination = label("Pantalla del juego", 15f, bold = true)
        content.addView(destination, rowParams(12))
        currentResolution = label("Actual: consultando…", 15f)
        physicalResolution = label("", 13f)
        stateLabel = label("", 13f)
        content.addView(currentResolution, rowParams(7))
        content.addView(physicalResolution, rowParams(3))
        content.addView(stateLabel, rowParams(5))

        content.addView(label("En duplicación, usa la resolución del monitor. El cambio afecta al tamaño lógico de esta pantalla y puede cambiar la imagen del teléfono.", 14f), rowParams(18))
        val dimensions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        widthInput = dimensionInput("Ancho del juego en píxeles")
        heightInput = dimensionInput("Alto del juego en píxeles")
        widthInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        dimensions.addView(inputColumn("Ancho (px)", widthInput), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(7) })
        dimensions.addView(inputColumn("Alto (px)", heightInput), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(7) })
        content.addView(dimensions, rowParams(18))
        content.addView(label("De 500 a 8192 píxeles por lado.", 12f), rowParams(5))

        applyButton = button("Aplicar resolución") { apply() }
        restoreButton = button("Restaurar resolución anterior") { restore() }
        refreshButton = button("Actualizar estado") { read() }
        content.addView(applyButton, rowParams(18))
        content.addView(restoreButton, rowParams(8))
        content.addView(refreshButton, rowParams(8))
        progress = ProgressBar(this).apply {
            contentDescription = "Actualizando resolución"
            visibility = View.GONE
        }
        content.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })
        feedback = label("", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(feedback, rowParams(12))
        content.addView(label("Al aplicar o restaurar se detiene el mapeo. Vuelve al juego y revisa la calibración.", 13f), rowParams(18))
        setContentView(scroll)
        scroll.requestApplyInsets()
        refreshControls()
    }

    private fun dimensionInput(description: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(4))
        contentDescription = description
        textSize = 19f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(162, 182, 208))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.rgb(23, 42, 70))
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), Color.rgb(67, 101, 149))
        }
        minimumHeight = dp(52)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    }

    private fun inputColumn(title: String, input: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 13f), rowParams())
        addView(input, rowParams(6))
    }

    private fun read() {
        if (targetDisplayId < 0 || gameRotation !in 0..3) return
        request("Consultando pantalla…", operation = false) { callback ->
            DisplayResolutionController.read(targetDisplayId, callback)
        }
    }

    private fun apply() {
        val width = widthInput.text.toString().trim().toIntOrNull()
        val height = heightInput.text.toString().trim().toIntOrNull()
        widthInput.error = if (width == null || width !in MIN_SIZE..MAX_SIZE) "Escribe un valor entre 500 y 8192" else null
        heightInput.error = if (height == null || height !in MIN_SIZE..MAX_SIZE) "Escribe un valor entre 500 y 8192" else null
        if (width == null || height == null || width !in MIN_SIZE..MAX_SIZE || height !in MIN_SIZE..MAX_SIZE) return
        val id = snapshot?.getString("displayUniqueId").orEmpty()
        if (id.isEmpty()) return
        clearInputFocus()
        val naturalWidth = if (gameRotation % 2 == 1) height else width
        val naturalHeight = if (gameRotation % 2 == 1) width else height
        request("Aplicando resolución…", operation = true) { callback ->
            DisplayResolutionController.apply(targetDisplayId, id, naturalWidth, naturalHeight, callback)
        }
    }

    private fun restore() {
        val id = snapshot?.getString("displayUniqueId").orEmpty()
        if (id.isEmpty()) return
        clearInputFocus()
        request("Restaurando resolución anterior…", operation = true, replaceInputs = true) { callback ->
            DisplayResolutionController.restore(targetDisplayId, id, callback)
        }
    }

    private fun request(message: String, operation: Boolean, replaceInputs: Boolean = false,
        submit: ((Result<Bundle>) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        val token = ++requestId
        showFeedback(message, error = false)
        refreshControls()
        val observer = WeakReference(this)
        val complete: (Result<Bundle>) -> Unit = { outcome ->
            dispatchResult(observer, token, operation, replaceInputs, outcome)
        }
        try { submit(complete) } catch (failure: Exception) { complete(Result.failure(failure)) }
    }

    private fun completeRequest(operation: Boolean, replaceInputs: Boolean, outcome: Result<Bundle>) {
        busy = false
        outcome.fold(onSuccess = { value ->
            snapshot = Bundle(value)
            val succeeded = !operation || value.getBoolean("operationSucceeded", false)
            renderSnapshot(replaceInputs && succeeded)
            val response = value.getString("message").orEmpty()
            if (!succeeded) showFeedback(response.ifEmpty { "No se pudo cambiar la resolución." }, error = true)
            else if (operation) showFeedback(response.ifEmpty {
                if (value.getBoolean("changed", false)) "Resolución confirmada. Vuelve al juego y revisa la calibración."
                else "La pantalla ya utiliza esta resolución."
            }, error = false)
            else showFeedback(if (value.getBoolean("available", false)) "" else response.ifEmpty {
                "Esta pantalla no admite el ajuste de resolución."
            }, error = !value.getBoolean("available", false))
        }, onFailure = { failure ->
            if (!operation) snapshot = null
            showFeedback(failure.localizedMessage ?: "No se pudo consultar la resolución.", error = true)
        })
        refreshControls()
    }

    private fun renderSnapshot(replaceInputs: Boolean) {
        val value = snapshot ?: return
        destination.text = value.getString("displayName").orEmpty().ifEmpty { "Pantalla del juego" }
        val naturalWidth = value.getInt("width", 0)
        val naturalHeight = value.getInt("height", 0)
        currentResolution.text = if (naturalWidth > 0 && naturalHeight > 0)
            "Actual: ${orientedSize(naturalWidth, naturalHeight)}" else "Actual: no disponible"
        val physicalWidth = value.getInt("physicalWidth", 0)
        val physicalHeight = value.getInt("physicalHeight", 0)
        physicalResolution.text = if (physicalWidth > 0 && physicalHeight > 0)
            "Resolución física: ${orientedSize(physicalWidth, physicalHeight)}" else ""
        stateLabel.text = when {
            value.getBoolean("managed", false) -> "Ajuste de UwU Mouse activo"
            value.getBoolean("hasOverride", false) -> "La pantalla ya tenía una resolución personalizada"
            else -> "Sin ajuste de UwU Mouse"
        }
        if (naturalWidth > 0 && naturalHeight > 0 && (!initializedInputs || replaceInputs)) {
            widthInput.setText((if (gameRotation % 2 == 1) naturalHeight else naturalWidth).toString())
            heightInput.setText((if (gameRotation % 2 == 1) naturalWidth else naturalHeight).toString())
            initializedInputs = true
        }
    }

    private fun orientedSize(naturalWidth: Int, naturalHeight: Int): String =
        if (gameRotation % 2 == 1) "$naturalHeight × $naturalWidth" else "$naturalWidth × $naturalHeight"

    private fun refreshControls() {
        val value = snapshot
        val available = value?.getBoolean("available", false) == true &&
            !value?.getString("displayUniqueId").isNullOrEmpty()
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        widthInput.isEnabled = !busy && available
        heightInput.isEnabled = !busy && available
        applyButton.isEnabled = !busy && available
        restoreButton.isEnabled = !busy && available && value?.getBoolean("canRestore", false) == true
        refreshButton.isEnabled = !busy && targetDisplayId >= 0 && gameRotation in 0..3
    }

    private fun clearInputFocus() {
        currentFocus?.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(widthInput.windowToken, 0)
    }

    private fun showFeedback(message: String, error: Boolean) {
        feedback.text = message
        feedback.setTextColor(if (error) Color.rgb(255, 171, 183) else Color.rgb(175, 218, 255))
    }

    private fun button(title: String, clicked: () -> Unit) = Button(this).apply {
        text = title
        contentDescription = title
        isAllCaps = false
        textSize = 14f
        minimumHeight = dp(48)
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(39, 86, 150), Color.rgb(36, 47, 64)),
        )
        setOnClickListener { clicked() }
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(220, 233, 250))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rowParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()

    companion object {
        private const val EXTRA_DISPLAY_ID = "displayId"
        private const val EXTRA_GAME_ROTATION = "gameRotation"
        private const val STATE_WIDTH = "resolutionWidth"
        private const val STATE_HEIGHT = "resolutionHeight"
        private const val MIN_SIZE = 500
        private const val MAX_SIZE = 8192

        private fun dispatchResult(observer: WeakReference<DisplayResolutionActivity>, token: Long,
            operation: Boolean, replaceInputs: Boolean, outcome: Result<Bundle>) {
            observer.get()?.runOnUiThread {
                val activity = observer.get()
                if (activity != null && activity.requestId == token && !activity.isFinishing && !activity.isDestroyed)
                    activity.completeRequest(operation, replaceInputs, outcome)
            }
        }

        fun launch(context: Context, displayId: Int, gameRotation: Int) {
            require(displayId >= 0 && gameRotation in 0..3)
            val intent = Intent(context, DisplayResolutionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_DISPLAY_ID, displayId)
                .putExtra(EXTRA_GAME_ROTATION, gameRotation)
            context.startActivity(intent, ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle())
        }
    }
}
