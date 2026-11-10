package dev.uwumouse.app.editor

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import dev.uwumouse.presentation.R
import java.lang.ref.WeakReference

/** Inline draft controls. Only Apply/restore invoke the bound control-plane port. */
internal class DisplayResolutionSettingsPanel(
    context: Context,
    private val port: DisplayResolutionSettingsPort,
    private val onEditingChanged: (Boolean) -> Unit,
    private val onContentChanged: () -> Unit,
) : LinearLayout(context), AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var state: DisplayResolutionSettingsState? = null
    private var referenceRotation: Int? = null
    private var draftEnabled = false
    private var busy = false
    private var closed = false
    private var editing = false
    private var initializedInputs = false
    private var requestId = 0L
    private var renderingSwitch = false
    private var pendingEditField: EditText? = null
    private val fieldOrigin = IntArray(2)
    private val ownerOrigin = IntArray(2)
    private val focusCheck = Runnable { setEditing(widthInput.hasFocus() || heightInput.hasFocus()) }
    private val beginEditing = Runnable {
        val field = pendingEditField
        pendingEditField = null
        if (field != null && !closed && !busy && draftEnabled && field.isEnabled && isAttachedToWindow) {
            setEditing(true)
            field.requestFocus()
            field.selectAll()
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    private val toggle = Switch(context)
    private val widthInput = input("Ancho del juego en píxeles", EditorInfo.IME_ACTION_NEXT)
    private val heightInput = input("Alto del juego en píxeles", EditorInfo.IME_ACTION_DONE)
    private val apply: ImageButton = action("Aplicar cambios", R.drawable.mm_check) { applyDraft() }
    private val reset: ImageButton = action("Restablecer", R.drawable.mm_reset) { reset() }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val feedback = text("", 11f)

    init {
        orientation = VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        setPadding(0, dp(10), 0, 0)
        toggle.apply {
            text = "Resolución personalizada"
            textSize = 12f
            setTextColor(Color.WHITE)
            contentDescription = "Resolución personalizada"
            ToggleStyle.apply(this)
            val icon = context.getDrawable(R.drawable.mm_setting_resolution)?.mutate()
            icon?.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(8)
            setOnCheckedChangeListener { _, checked ->
                if (!renderingSwitch) toggleDraft(checked)
            }
        }
        addView(toggle, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        val fields = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
        fields.addView(fieldColumn("Ancho (px)", widthInput), LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        fields.addView(fieldColumn("Alto (px)", heightInput), LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        fields.addView(apply, LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        fields.addView(reset, LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        addView(fields, LayoutParams(-1, -2))
        progress.isIndeterminate = true
        progress.visibility = GONE
        addView(progress, LayoutParams(-1, dp(3)).apply { topMargin = dp(6) })
        feedback.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        feedback.visibility = GONE
        addView(feedback, LayoutParams(-1, -2).apply { topMargin = dp(5) })
        refreshControls()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        if (!closed && state == null && !busy) {
            request("Consultando resolución…", operation = false) { port.read(it) }
        }
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    override fun close() {
        if (closed) return
        closed = true
        requestId++
        finishEditing()
        main.removeCallbacks(focusCheck)
    }

    /** The menu supplies its local coordinates, including manually routed mouse clicks. */
    fun releaseEditingOutside(owner: View, x: Float, y: Float) {
        if (!editing || closed) return
        owner.getLocationOnScreen(ownerOrigin)
        fun inside(field: EditText): Boolean {
            if (!field.isShown || !field.isEnabled) return false
            field.getLocationOnScreen(fieldOrigin)
            val left = fieldOrigin[0] - ownerOrigin[0]
            val top = fieldOrigin[1] - ownerOrigin[1]
            return x >= left && x < left + field.width && y >= top && y < top + field.height
        }
        if (!inside(widthInput) && !inside(heightInput)) finishEditing()
    }

    fun finishEditing() {
        main.removeCallbacks(beginEditing)
        pendingEditField = null
        main.removeCallbacks(focusCheck)
        widthInput.clearFocus()
        heightInput.clearFocus()
        requestFocus()
        main.removeCallbacks(focusCheck)
        setEditing(false)
    }

    private fun toggleDraft(checked: Boolean) {
        if (busy || closed) { refreshControls(); return }
        if (checked) {
            draftEnabled = true
            showFeedback("", false)
            refreshControls()
            return
        }
        finishEditing()
        val current = state
        if (current?.managed == true) {
            if (current.canRestore) restore(current)
            else {
                draftEnabled = true
                showFeedback(current.message.ifEmpty { "No se puede restablecer esta pantalla en este momento." }, true)
                refreshControls()
            }
        } else {
            // Turning off an unapplied draft is local; it must not touch WindowManager.
            draftEnabled = false
            showFeedback("", false)
            refreshControls()
        }
    }

    private fun applyDraft() {
        val current = state ?: return
        if (busy || !draftEnabled || !current.available || current.displayUniqueId.isBlank()) return
        val width = widthInput.text.toString().trim().toIntOrNull()
        val height = heightInput.text.toString().trim().toIntOrNull()
        widthInput.error = if (width == null || width !in MIN_SIZE..MAX_SIZE) "500–8192 px" else null
        heightInput.error = if (height == null || height !in MIN_SIZE..MAX_SIZE) "500–8192 px" else null
        if (width == null || height == null || width !in MIN_SIZE..MAX_SIZE || height !in MIN_SIZE..MAX_SIZE) return
        val rotation = referenceRotation ?: return
        finishEditing()
        val naturalWidth = if (rotation % 2 == 1) height else width
        val naturalHeight = if (rotation % 2 == 1) width else height
        request("Aplicando resolución…", operation = true) { callback ->
            port.apply(current.displayUniqueId, naturalWidth, naturalHeight, callback)
        }
    }

    private fun reset() {
        if (busy || closed) return
        finishEditing()
        val current = state
        if (current?.canRestore == true) restore(current)
        else if (current?.managed == true) {
            showFeedback(current.message.ifEmpty { "No se puede restablecer esta pantalla en este momento." }, true)
        } else {
            draftEnabled = false
            fillDimensions(current)
            widthInput.error = null
            heightInput.error = null
            showFeedback("", false)
            refreshControls()
        }
    }

    private fun restore(current: DisplayResolutionSettingsState) {
        if (current.displayUniqueId.isBlank()) return
        request("Restableciendo resolución…", operation = true, restoring = true) { callback ->
            port.restore(current.displayUniqueId, callback)
        }
    }

    private fun request(message: String, operation: Boolean, restoring: Boolean = false,
        submit: ((Result<DisplayResolutionSettingsState>) -> Unit) -> Unit) {
        if (busy || closed) return
        busy = true
        val token = ++requestId
        showFeedback(message, false)
        refreshControls()
        val observer = WeakReference(this)
        val complete: (Result<DisplayResolutionSettingsState>) -> Unit = { outcome ->
            deliver(observer, token, operation, restoring, outcome)
        }
        try { submit(complete) } catch (failure: Exception) { complete(Result.failure(failure)) }
    }

    private fun complete(operation: Boolean, restoring: Boolean, outcome: Result<DisplayResolutionSettingsState>) {
        busy = false
        outcome.fold(onSuccess = { next ->
            state = next
            if (referenceRotation == null && next.gameRotation in 0..3) referenceRotation = next.gameRotation
            val succeeded = !operation || next.operationSucceeded
            if (!initializedInputs || (succeeded && restoring)) fillDimensions(next)
            if (!operation || !succeeded || restoring) draftEnabled = next.managed
            when {
                !succeeded -> showFeedback(next.message.ifEmpty { "No se pudo cambiar la resolución." }, true)
                !next.available -> showFeedback(next.message.ifEmpty { "Esta pantalla no admite el ajuste de resolución." }, true)
                operation -> showFeedback(next.message.ifEmpty {
                    if (restoring) "Resolución anterior restablecida. Revisa la calibración."
                    else "Resolución aplicada. Revisa la calibración antes de jugar."
                }, false)
                else -> showFeedback("", false)
            }
        }, onFailure = { failure ->
            if (operation) draftEnabled = state?.managed == true
            showFeedback(failure.localizedMessage ?: "No se pudo consultar la resolución.", true)
        })
        refreshControls()
        onContentChanged()
    }

    private fun fillDimensions(value: DisplayResolutionSettingsState?) {
        val rotated = (referenceRotation ?: value?.gameRotation ?: 0) % 2 == 1
        val width = if (rotated) value?.naturalHeight else value?.naturalWidth
        val height = if (rotated) value?.naturalWidth else value?.naturalHeight
        widthInput.setText(width?.takeIf { it > 0 }?.toString().orEmpty())
        heightInput.setText(height?.takeIf { it > 0 }?.toString().orEmpty())
        initializedInputs = value != null && width != null && height != null && width > 0 && height > 0
    }

    private fun refreshControls() {
        val current = state
        val available = current != null && current.available && current.displayUniqueId.isNotBlank() &&
            referenceRotation?.let { it in 0..3 } == true
        renderingSwitch = true
        toggle.isChecked = draftEnabled
        renderingSwitch = false
        toggle.isEnabled = !busy && available
        val editable = !busy && available && draftEnabled
        widthInput.isEnabled = editable
        heightInput.isEnabled = editable
        widthInput.alpha = if (editable) 1f else .4f
        heightInput.alpha = if (editable) 1f else .4f
        apply.isEnabled = editable
        apply.alpha = if (editable) 1f else .4f
        // Restore stays present and remains usable independently of the draft switch.
        reset.isEnabled = !busy
        reset.alpha = if (busy) .4f else 1f
        progress.visibility = if (busy) VISIBLE else GONE
    }

    private fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        onEditingChanged(value)
    }

    @SuppressLint("ClickableViewAccessibility") // The first click ends before changing native pointer ownership.
    private fun input(description: String, action: Int): EditText = object : EditText(context) {
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && hasFocus() && !closed)
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }.apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(4))
        contentDescription = description
        textSize = 14f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(150, 174, 204))
        setPadding(dp(9), dp(7), dp(9), dp(7))
        minimumHeight = dp(44)
        background = GradientDrawable().apply {
            setColor(Color.rgb(17, 34, 60))
            cornerRadius = dp(7).toFloat()
            setStroke(dp(1), Color.rgb(57, 85, 123))
        }
        imeOptions = action
        var waitingForUp = false
        setOnTouchListener { _, event ->
            if (!isEnabled || editing) false
            else when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { waitingForUp = true; isPressed = true; true }
                MotionEvent.ACTION_MOVE -> waitingForUp
                MotionEvent.ACTION_UP -> {
                    val activate = waitingForUp && event.x >= 0 && event.y >= 0 && event.x < width && event.y < height
                    waitingForUp = false
                    isPressed = false
                    if (activate) performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { waitingForUp = false; isPressed = false; true }
                else -> waitingForUp
            }
        }
        setOnClickListener {
            if (!editing && isEnabled && !closed) {
                pendingEditField = this
                main.removeCallbacks(beginEditing)
                main.post(beginEditing)
            }
        }
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            main.removeCallbacks(focusCheck)
            if (focused && !editing && !closed) {
                pendingEditField = this
                main.removeCallbacks(beginEditing)
                main.post(beginEditing)
            } else if (!focused) main.post(focusCheck)
        }
        setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event == null || event.action == KeyEvent.ACTION_UP) finishEditing()
                true
            } else false
        }
        setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) finishEditing()
                true
            } else false
        }
    }

    private fun fieldColumn(title: String, field: EditText) = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(text(title, 10f), LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        addView(field, LayoutParams(-1, dp(44)))
    }

    private fun action(title: String, icon: Int, clicked: () -> Unit): ImageButton = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = null
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        minimumWidth = 0
        minimumHeight = 0
        isFocusable = true
        contentDescription = title
        tooltipText = title
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(34, 65, 117), Color.rgb(18, 39, 78))).apply { cornerRadius = dp(8).toFloat() }
        setOnClickListener { clicked() }
    }

    private fun text(value: String, size: Float): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(224, 239, 255))
    }

    private fun showFeedback(message: String, error: Boolean) {
        feedback.text = message
        feedback.visibility = if (message.isEmpty()) GONE else VISIBLE
        feedback.setTextColor(if (error) Color.rgb(255, 179, 190) else Color.rgb(176, 213, 250))
        onContentChanged()
    }

    private fun dp(value: Int) = (context.resources.displayMetrics.density * value + .5f).toInt()

    companion object {
        private const val MIN_SIZE = 500
        private const val MAX_SIZE = 8192
        private val main = Handler(Looper.getMainLooper())

        private fun deliver(observer: WeakReference<DisplayResolutionSettingsPanel>, token: Long,
            operation: Boolean, restoring: Boolean, outcome: Result<DisplayResolutionSettingsState>) {
            fun complete() {
                val panel = observer.get()
                if (panel != null && !panel.closed && panel.isAttachedToWindow && panel.requestId == token)
                    panel.complete(operation, restoring, outcome)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) complete() else main.post { complete() }
        }
    }
}
