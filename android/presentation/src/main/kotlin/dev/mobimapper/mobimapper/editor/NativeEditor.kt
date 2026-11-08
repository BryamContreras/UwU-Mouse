package dev.mobimapper.mobimapper.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.TextKeyListener
import android.util.Log
import android.view.ActionMode
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.PointerIcon
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.mobimapper.presentation.R
import dev.mobimapper.mobimapper.profile.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private fun editorKeyLabel(code:Int):String=when(code) {
    0 -> "?"
    KeyEvent.KEYCODE_SPACE -> "ESP"
    KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT -> "Mayús"
    KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl"
    KeyEvent.KEYCODE_ALT_LEFT,KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
    KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_DEL -> "⌫"
    KeyEvent.KEYCODE_DPAD_UP -> "↑"
    KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
    KeyEvent.KEYCODE_DPAD_LEFT -> "←"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}

/** Main-thread editor. The session owner must finish touches and disable capture before show(). */
class NativeEditor(
    private val service: Context,
    private val overlayContext: Context = service,
    private val presentationHost: EditorPresentationHost,
) {
    private val windows = overlayContext.getSystemService(WindowManager::class.java)
    private var root: EditorRoot? = null
    val visible: Boolean get() = root != null

    fun show(profile: GameProfile, onSave: (GameProfile) -> Unit, onCancel: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Editor must be opened on the main thread" }
        close()
        val display = requireNotNull(if(overlayContext===service)
            service.getSystemService(DisplayManager::class.java).getDisplay(0) else overlayContext.display) { "Pantalla del juego no disponible" }
        check(display.isValid) { "La pantalla del juego ya no está disponible" }
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val hint = suggestedArea(profile.packageName, size.x, size.y, display.displayId)
        val draft = EditorDraft(profile, size.x, size.y, display.rotation, hint.area,
            displayScope=if(display.displayId==0) "phone" else "external",windowFrame=hint.windowFrame)
        val view = EditorRoot(overlayContext, draft, presentationHost.cursorPosition(),presentationHost.cursorSizeDp(), {
            onSave(it)
            close()
        }, {
            close()
            onCancel()
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            OverlayWindows.type(overlayContext),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "MobiMapper · Editor"
        }
        // Keep root null if token/display admission fails; coordinator cleanup stays idempotent.
        check(presentationHost.begin(profile.packageName,display.displayId)) {
            "No se pudo confirmar el juego antes de abrir el editor"
        }
        try { windows.addView(view, params) }
        catch(failure:RuntimeException) { presentationHost.end("window_admission_failed");throw failure }
        root = view
        view.requestFocus()
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Editor must be closed on the main thread" }
        presentationHost.end("editor_closed")
        val view = root ?: return
        root = null
        try { windows.removeViewImmediate(view) } catch (_: IllegalArgumentException) { /* Display removal may detach it first. */ }
    }

    private data class SuggestedArea(val area: GameArea, val windowFrame: GameArea?)
    private data class GameWindowBounds(val frame: Rect, val content: Rect, val focused: Boolean, val layer: Int) {
        val area: Long get() = frame.width().toLong() * frame.height()
    }

    private fun suggestedArea(packageName: String, width: Int, height: Int, displayId: Int): SuggestedArea {
        val rect = Rect(0, 0, width, height)
        // Resolve ownership and bounds only. No view text or descendants are traversed.
        val gameWindow = presentationHost.windows(displayId).mapNotNull { window ->
            if(window.displayId!=displayId || window.packageName!=packageName) return@mapNotNull null
            runCatching {
                val frame=Rect(window.frame)
                val content=Rect(window.content)
                if(frame.isEmpty) null else GameWindowBounds(frame,content,window.focused,window.layer)
            }.getOrNull()
        }.maxWithOrNull(compareBy<GameWindowBounds> { it.area }.thenBy { it.focused }.thenBy { it.layer })
        check(displayId==0 || gameWindow!=null) { "No se pudo confirmar la ventana del juego en esta pantalla" }
        if(gameWindow!=null) {
            check(rect.contains(gameWindow.frame)) { "Coloca toda la ventana del juego dentro de la pantalla" }
            check(rect.intersect(gameWindow.frame)) { "La ventana del juego está fuera de esta pantalla" }
            // Some desktop roots omit the caption while the APP window includes its complete frame.
            val content=Rect(rect)
            if(content.intersect(gameWindow.content) && content.width()>=32 && content.height()>=32) rect.set(content)
        }
        val visibleInsets = windows.maximumWindowMetrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar(),
        )
        val usable = Rect(visibleInsets.left, visibleInsets.top, width - visibleInsets.right, height - visibleInsets.bottom)
        check(usable.width()>=32 && usable.height()>=32 && rect.intersect(usable) && rect.width()>=32 && rect.height()>=32) {
            "No se pudo confirmar un área útil para el juego"
        }
        fun area(bounds:Rect)=GameArea(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat())
        // The useful area may exclude decoration. Calibration retains the complete APP frame.
        return SuggestedArea(area(rect),gameWindow?.frame?.let(::area))
    }

    private class EditorRoot(
        context: Context,
        private val draft: EditorDraft,
        initialPointer:PointF?,
        private val cursorSizeDp:Float,
        private val save: (GameProfile) -> Unit,
        private val cancel: () -> Unit,
    ) : FrameLayout(context) {
        private var cursorScreenX=initialPointer?.x ?: Float.NaN
        private var cursorScreenY=initialPointer?.y ?: Float.NaN
        private var cursorShown=initialPointer!=null
        private val cursorOrigin=IntArray(2)
        private val cursorFill=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(246,249,255);style=Paint.Style.FILL }
        private val cursorEdge=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(25,52,102);style=Paint.Style.STROKE;strokeWidth=1.1f;strokeJoin=Paint.Join.ROUND }
        private val cursorPath=android.graphics.Path().apply { moveTo(0f,0f);lineTo(1.4f,20f);lineTo(7.8f,13.2f);lineTo(17.5f,13.6f);close() }
        override fun onResolvePointerIcon(event:MotionEvent,pointerIndex:Int):PointerIcon =
            PointerIcon.getSystemIcon(context,PointerIcon.TYPE_NULL)
        private fun trackEditorPointer(event:MotionEvent) {
            if(!event.isFromSource(InputDevice.SOURCE_MOUSE)) return
            cursorScreenX=event.rawX;cursorScreenY=event.rawY
            cursorShown=event.actionMasked!=MotionEvent.ACTION_HOVER_EXIT
            postInvalidateOnAnimation()
        }
        override fun dispatchDraw(canvas:Canvas) {
            super.dispatchDraw(canvas)
            if(!cursorShown || !cursorScreenX.isFinite() || !cursorScreenY.isFinite()) return
            getLocationOnScreen(cursorOrigin)
            canvas.save();canvas.translate(cursorScreenX-cursorOrigin[0],cursorScreenY-cursorOrigin[1])
            val scale=resources.displayMetrics.density*cursorSizeDp/20f
            canvas.scale(scale,scale);canvas.drawPath(cursorPath,cursorFill);canvas.drawPath(cursorPath,cursorEdge);canvas.restore()
        }
        private val density = resources.displayMetrics.density
        private val refreshers = ArrayList<() -> Unit>()
        private var selected = EditorDraft.Control.JOYSTICK
        private var pendingBinding: String? = null
        private var capturedKey = -1
        private var capturedKeyMouseDevice:Int?=null
        private var capturedSuspendMouseButton=0
        private var capturedSuspendMouseDevice=-1
        private var capturedSuspendMouseTouch=false
        private var lastSuspendMouseButton=0
        private var lastSuspendMouseReleaseTime=Long.MIN_VALUE
        private var cameraRegionVisible = false
        private var firePlacement = false
        private var aimPlacement = false
        private var actionPlacement="tap"
        private var pendingPlacement: EditorDraft.Control? = null
        private var pendingMacroPoint: Int? = null
        private var macroPointsVisible = false
        private val toolCells=linkedMapOf<String,View>()
        private var highlightedTool=""
        private val hint = TextView(context)
        private val instruction = TextView(context)
        private val error = TextView(context)
        private val canvas = ControlsCanvas(context, draft, { selected }, { select(it) }, { refreshDrag() },
            { beginBinding(it) }, { pendingPlacement }, { control,x,y -> place(control,x,y) }, { control -> properties(control) }, { cameraRegionVisible },
            { pendingMacroPoint }, { index,x,y -> placeMacroPoint(index,x,y) }, { macroPointsVisible || pendingMacroPoint!=null }, { removeSelected(it) })
        private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val scroller = ScrollView(context)
        private val palette = LinearLayout(context)
        private var palettePlaced = false
        private data class WaitInput(val macroId:String,val step:Int)
        private val invalidWaits=linkedMapOf<WaitInput,String>()
        private val waitFields=linkedMapOf<WaitInput,EditText>()
        private val invalidIntervals=linkedMapOf<Int,String>()
        private val intervalFields=linkedMapOf<Int,EditText>()
        private var activeNumberPad:View?=null
        private var saveButton:Button?=null
        private val noTextPopup=object:ActionMode.Callback {
            override fun onCreateActionMode(mode:ActionMode?,menu:Menu?)=false
            override fun onPrepareActionMode(mode:ActionMode?,menu:Menu?)=false
            override fun onActionItemClicked(mode:ActionMode?,item:MenuItem?)=false
            override fun onDestroyActionMode(mode:ActionMode?)=Unit
        }
        private var dragRefreshPending = false
        private val dragPanelRefresh = Runnable {
            dragRefreshPending = false
            if (isAttachedToWindow && scroller.visibility == View.VISIBLE) refresh()
        }

        init {
            pointerIcon=android.view.PointerIcon.getSystemIcon(context,android.view.PointerIcon.TYPE_ARROW)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription="UwU Mouse · editor"
            isFocusableInTouchMode = true
            setBackgroundColor(Color.argb(18, 4, 12, 30))
            addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            palette.apply { orientation=LinearLayout.VERTICAL; setPadding(dp(5),0,dp(5),dp(5)); background=background(24) }
            val paletteHandle=LinearLayout(context).apply {
                gravity=Gravity.CENTER; orientation=LinearLayout.HORIZONTAL; contentDescription="Arrastrar paleta"
                addView(TextView(context).apply { text="Controles";textSize=11f;setTextColor(Color.rgb(201,214,234)) })
            }
            palette.addView(paletteHandle,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(28)))
            installPaletteDrag(paletteHandle)
            val paletteGrid = GridLayout(context).apply { columnCount=5; rowCount=2 }
            palette.addView(paletteGrid)
            val tools=listOf(Triple("WASD",R.drawable.mm_joystick,EditorDraft.Control.JOYSTICK),
                Triple("C\u00e1mara",R.drawable.mm_camera,EditorDraft.Control.CAMERA),
                Triple("Acci\u00f3n",R.drawable.mm_action,EditorDraft.Control.ACTION),
                Triple("\u00c1rea",R.drawable.mm_area,EditorDraft.Control.AREA))
            for((name,icon,control) in tools) {
                paletteGrid.addView(tool(name,icon) {
                    if(control==EditorDraft.Control.JOYSTICK) draft.replace(draft.value.copy(joystick=draft.value.joystick.copy(enabled=true)))
                    if(control==EditorDraft.Control.CAMERA) draft.replace(draft.value.copy(camera=draft.value.camera.copy(enabled=true)))
                    pendingBinding=null; pendingMacroPoint=null; macroPointsVisible=false; cameraRegionVisible=false; select(control)
                    if(control == EditorDraft.Control.ACTION) {
                        firePlacement=false; aimPlacement=false;actionPlacement="tap"; pendingPlacement=control; scroller.visibility=View.GONE; refresh()
                    } else if(control==EditorDraft.Control.CAMERA) { pendingPlacement=null; scroller.visibility=View.GONE; refresh() } else { pendingPlacement=null; showPanel() }
                },GridLayout.LayoutParams().apply { width=dp(64); height=dp(64); setMargins(dp(3),dp(3),dp(3),dp(3)) })
            }
            paletteGrid.addView(tool("Disparo",R.drawable.mm_fire) {
                pendingBinding=null; pendingMacroPoint=null; macroPointsVisible=false; cameraRegionVisible=false; selected=EditorDraft.Control.ACTION
                firePlacement=true; aimPlacement=false;actionPlacement="tap"; pendingPlacement=EditorDraft.Control.ACTION; scroller.visibility=View.GONE; refresh()
            },GridLayout.LayoutParams().apply { width=dp(64); height=dp(64); setMargins(dp(3),dp(3),dp(3),dp(3)) })
            paletteGrid.addView(tool("Apuntar",R.drawable.mm_aim) {
                pendingBinding=null; pendingMacroPoint=null; macroPointsVisible=false; cameraRegionVisible=false; selected=EditorDraft.Control.ACTION
                firePlacement=false; aimPlacement=true;actionPlacement="tap"; pendingPlacement=EditorDraft.Control.ACTION; scroller.visibility=View.GONE; refresh()
            },GridLayout.LayoutParams().apply { width=dp(64); height=dp(64); setMargins(dp(3),dp(3),dp(3),dp(3)) })
            paletteGrid.addView(tool("Macro",R.drawable.mm_macro) {
                pendingBinding=null; pendingMacroPoint=null; macroPointsVisible=false; cameraRegionVisible=false
                firePlacement=false; aimPlacement=false; selected=EditorDraft.Control.MACRO
                pendingPlacement=EditorDraft.Control.MACRO; scroller.visibility=View.GONE; refresh()
            },GridLayout.LayoutParams().apply { width=dp(64); height=dp(64); setMargins(dp(3),dp(3),dp(3),dp(3)) })
            for((name,behavior,icon) in listOf(Triple("Repetir","repeat",R.drawable.mm_repeat),Triple("Mapa/Mochila","cursorToggle",R.drawable.mm_mapbag))) {
                paletteGrid.addView(tool(name,icon) {
                    pendingBinding=null;pendingMacroPoint=null;macroPointsVisible=false;cameraRegionVisible=false
                    firePlacement=false;aimPlacement=false;actionPlacement=behavior;selected=EditorDraft.Control.ACTION
                    pendingPlacement=EditorDraft.Control.ACTION;scroller.visibility=View.GONE;refresh()
                },GridLayout.LayoutParams().apply { width=dp(64);height=dp(64);setMargins(dp(3),dp(3),dp(3),dp(3)) })
            }
            val actions=LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(3),dp(7),dp(3),dp(2))
            }
            actions.addView(button("Guardar") {
                if(showInvalidWait()) return@button
                try { save(draft.save().let { it.copy(shortcuts=it.shortcuts.copy(stop=0,edit=0)) }) }
                catch(e:Exception) { error.text=e.message ?: "No se pudo guardar"; scroller.visibility=View.VISIBLE }
            }.apply { saveButton=this; background=gradient(Color.rgb(87,166,255),Color.rgb(43,103,223),12,Color.rgb(151,209,255)); setTextColor(Color.WHITE); typeface=android.graphics.Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(0,dp(38),1f))
            actions.addView(button("Cancelar") { cancel() }.apply { background=gradient(Color.rgb(34,54,88),Color.rgb(19,33,58),12,Color.rgb(66,94,135)); setTextColor(Color.rgb(224,236,255)) },LinearLayout.LayoutParams(0,dp(38),1f).apply { leftMargin=dp(8) })
            palette.addView(actions,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
            addView(palette,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT,Gravity.TOP or Gravity.START).apply { topMargin=dp(8); leftMargin=dp(8) })
            hint.apply { textSize=12f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; setPadding(dp(14),dp(8),dp(14),dp(8)); background=background(12) }
            addView(hint,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(8) })
            val panel=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(6),dp(12),dp(8)); background=background(16)
            }
            val heading=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
            instruction.apply { setTextColor(Color.rgb(163,208,255)); textSize=13f; typeface=android.graphics.Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER_VERTICAL; setPadding(0,0,dp(4),0) }
            heading.addView(instruction,LinearLayout.LayoutParams(0,dp(44),1f))
            installPanelDrag(instruction)
            heading.addView(ImageButton(context).apply {
                setImageResource(R.drawable.mm_close); imageTintList=null; scaleType=ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6),dp(6),dp(6),dp(6)); background=gradient(Color.rgb(31,53,88),Color.rgb(18,34,61),10)
                contentDescription="Cerrar panel"; setOnClickListener { closePanel() }
            },LinearLayout.LayoutParams(dp(44),dp(44)))
            panel.addView(heading)
            error.apply { setTextColor(Color.rgb(255,189,88)); textSize=12f; maxLines=2 }
            panel.addView(error); panel.addView(body)
            scroller.addView(panel); scroller.visibility=View.GONE; scroller.isVerticalScrollBarEnabled=false
            addView(scroller,LayoutParams(min(dp(280),draft.value.calibration.screenWidth-dp(16)), min(dp(360),(draft.value.calibration.screenHeight*.76f).toInt()),Gravity.CENTER))
            refresh()

        }

        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            super.onSizeChanged(w,h,oldw,oldh); palettePlaced=false
        }
        override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
            super.onLayout(changed,left,top,right,bottom)
            if(!palettePlaced) {
                palettePlaced=true
                positionPalette((width-palette.width)/2f,0f)
            }
        }
        private fun positionPalette(x:Float,y:Float) {
            val maxX=maxOf(0,width-palette.width).toFloat()
            val maxY=maxOf(0,height-palette.height).toFloat()
            val px=x.coerceIn(0f,maxX)
            val py=y.coerceIn(0f,maxY)
            palette.x=px; palette.y=py
        }
        @SuppressLint("ClickableViewAccessibility")
        private fun installPaletteDrag(handle:View) {
            var rawX=0f;var rawY=0f;var startX=0f;var startY=0f
            handle.isClickable=true
            handle.setOnTouchListener { _,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { rawX=event.rawX;rawY=event.rawY;startX=palette.x;startY=palette.y;true }
                    MotionEvent.ACTION_MOVE -> { positionPalette(startX+event.rawX-rawX,startY+event.rawY-rawY);true }
                    MotionEvent.ACTION_UP -> {
                        positionPalette(startX+event.rawX-rawX,startY+event.rawY-rawY)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        private fun installPanelDrag(handle:View) {
            var rawX=0f;var rawY=0f;var startX=0f;var startY=0f
            handle.setOnTouchListener { _,event ->
                fun move() {
                    scroller.x=(startX+event.rawX-rawX).coerceIn(0f,maxOf(0,width-scroller.width).toFloat())
                    scroller.y=(startY+event.rawY-rawY).coerceIn(0f,maxOf(0,height-scroller.height).toFloat())
                }
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { rawX=event.rawX;rawY=event.rawY;startX=scroller.x;startY=scroller.y;true }
                    MotionEvent.ACTION_MOVE -> { move();true }
                    MotionEvent.ACTION_UP -> { move();true }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        private fun showInvalidWait():Boolean {
            invalidWaits.keys.removeAll { key ->
                val macro=draft.value.macros.firstOrNull { it.id==key.macroId }
                macro==null || key.step !in macro.steps.indices
            }
            invalidIntervals.keys.removeAll { draft.value.actions.getOrNull(it)?.behavior!="repeat" }
            val bad=invalidWaits.keys.firstOrNull()
            val interval=invalidIntervals.keys.firstOrNull()
            if(bad==null && interval==null) return false
            val input=if(bad!=null) {
                val index=draft.value.macros.indexOfFirst { it.id==bad.macroId }
                draft.selectMacro(index);selected=EditorDraft.Control.MACRO;showPanel()
                error.text="Revisa la espera: 0–5000 ms";waitFields[bad]
            } else {
                val actionIndex=requireNotNull(interval)
                draft.selectAction(actionIndex);selected=EditorDraft.Control.ACTION;showPanel()
                error.text="Revisa el intervalo: 50–5000 ms";intervalFields[actionIndex]
            }
            input?.let { field -> field.post { if(field.isAttachedToWindow) { field.requestFocus();field.requestRectangleOnScreen(Rect(0,0,field.width,field.height+dp(22)),true) } } }
            return true
        }
        private fun removeSelected(control:EditorDraft.Control) {
            closePanel()
            pendingPlacement=null;pendingBinding=null;pendingMacroPoint=null
            when(control) {
                EditorDraft.Control.JOYSTICK -> draft.removeJoystick()
                EditorDraft.Control.CAMERA -> draft.removeCamera()
                EditorDraft.Control.ACTION -> {
                    val index=draft.selectedAction
                    val entries=invalidIntervals.toMap();invalidIntervals.clear()
                    for((owner,value) in entries) if(owner!=index) invalidIntervals[if(owner>index) owner-1 else owner]=value
                    draft.removeAction()
                }
                EditorDraft.Control.MACRO -> {
                    val id=draft.macro().id;invalidWaits.keys.removeAll { it.macroId==id };draft.removeMacro()
                }
                EditorDraft.Control.AREA -> Unit
            }
            refresh()
        }

        private fun gradient(top:Int,bottom:Int,radius:Int,stroke:Int?=null) = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,intArrayOf(top,bottom),
        ).apply {
            cornerRadius=dp(radius).toFloat()
            if(stroke!=null) setStroke(dp(1),stroke)
        }
        private fun background(radius:Int) = gradient(Color.argb(244,19,38,70),Color.argb(244,8,17,34),radius,Color.rgb(53,80,121))
        private fun toolBackground(active:Boolean) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if(active) intArrayOf(Color.argb(230,36,112,218),Color.argb(230,16,54,117))
            else intArrayOf(Color.argb(200,28,51,85),Color.argb(200,12,28,52)),
        ).apply {
            shape=GradientDrawable.OVAL
            setStroke(dp(1),if(active) Color.rgb(140,200,255) else Color.rgb(53,79,116))
        }
        private fun tool(name:String,icon:Int,action:()->Unit):View = LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; background=toolBackground(false)
            isClickable=true; contentDescription=name
            addView(ImageView(context).apply { setImageResource(icon); imageTintList=null; scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(32),dp(32)))
            addView(TextView(context).apply { text=name; textSize=10.5f; typeface=android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(239,246,255)); gravity=Gravity.CENTER; setPadding(0,dp(3),0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            toolCells[name]=this
            setOnClickListener { action() }
        }
        private fun beginBinding(control:EditorDraft.Control) {
            if(control==EditorDraft.Control.ACTION && isFireAction()) { properties(control); return }
            pendingBinding=when(control) { EditorDraft.Control.ACTION -> "action"; EditorDraft.Control.CAMERA -> "cameraToggle"; EditorDraft.Control.MACRO -> "macro"; else -> null }
            pendingMacroPoint=null; cameraRegionVisible=false
            scroller.visibility=View.GONE; requestFocus(); refresh()
        }
        private fun closePanel() {
            pendingBinding=null; pendingPlacement=null; pendingMacroPoint=null; macroPointsVisible=false; cameraRegionVisible=false
            if(selected==EditorDraft.Control.AREA) selected=EditorDraft.Control.JOYSTICK
            removeCallbacks(dragPanelRefresh); dragRefreshPending=false
            activeNumberPad=null;waitFields.clear();intervalFields.clear();refreshers.clear();body.removeAllViews()
            scroller.visibility=View.GONE; refresh()
        }
        private fun properties(control:EditorDraft.Control) {
            pendingBinding=null; pendingPlacement=null; pendingMacroPoint=null; selected=control
            cameraRegionVisible=control==EditorDraft.Control.CAMERA
            macroPointsVisible=control==EditorDraft.Control.MACRO
            showPanel()
        }
        private fun place(control:EditorDraft.Control,x:Float,y:Float) {
            pendingPlacement=null
            try {
                if(control==EditorDraft.Control.ACTION) when(actionPlacement) {
                    "repeat" -> draft.addRepeat(x,y)
                    "cursorToggle" -> draft.addCursorAction(x,y)
                    else -> draft.addAction(x,y,fire=firePlacement,aim=aimPlacement)
                }
                if(control==EditorDraft.Control.MACRO) { draft.addMacro(x,y); macroPointsVisible=true; beginBinding(control) }
                else if(!firePlacement && !aimPlacement) beginBinding(control) else refresh()
            } catch(e:IllegalArgumentException) { Toast.makeText(context,e.message,Toast.LENGTH_SHORT).show(); refresh() }
        }
        private fun placeMacroPoint(index:Int,x:Float,y:Float) {
            pendingMacroPoint=null
            try {
                if(index<0) draft.addMacroStep(x,y) else draft.moveMacroStep(index,x,y)
                showPanel(false)
            } catch(e:IllegalArgumentException) { showPanel(false);error.text=e.message }
        }
        private fun showPanel(resetScroll:Boolean=true) {
            val recenter=resetScroll || scroller.visibility!=View.VISIBLE
            val previousScroll=if(resetScroll) 0 else scroller.scrollY
            activeNumberPad=null
            waitFields.clear();intervalFields.clear()
            pendingBinding=null; pendingPlacement=null;pendingMacroPoint=null; refreshers.clear(); body.removeAllViews(); error.text=""
            when(selected) {
                EditorDraft.Control.JOYSTICK -> { addGeometry(); addJoystickBindings() }
                EditorDraft.Control.CAMERA -> addGeometry()
                EditorDraft.Control.ACTION -> if(isFireAction()) { addPrecisionAim();addFireCadence() } else {
                    when(draft.action().behavior) {
                        "repeat" -> addRepeatInterval()
                        "cursorToggle" -> helpLabel("Cursor", "Alterna entre cámara y cursor al pulsar esta acción.")
                    }
                }
                EditorDraft.Control.MACRO -> addMacroPanel()
                EditorDraft.Control.AREA -> addArea()
            }
            macroPointsVisible=selected==EditorDraft.Control.MACRO
            if(recenter) { scroller.translationX=0f;scroller.translationY=0f }
            scroller.visibility=if(body.childCount==0) View.GONE else View.VISIBLE
            scroller.scrollTo(0,previousScroll); refresh()
        }

        private fun addGeometry() {
            if(selected == EditorDraft.Control.CAMERA) { addCameraSuspend(); addCameraRegion(); addCameraFrequency() }
            if(selected == EditorDraft.Control.JOYSTICK) slider("Radio", 2, 40, { (draft.value.joystick.radius * 100).toInt() }, { "$it%" }) {
                val a = draft.value.calibration.area
                draft.joystickRadius(it / 100f * min(a.width, a.height))
            }
            if(selected == EditorDraft.Control.CAMERA && customCameraRegion()) slider("Ancho", 4, 95, { (draft.value.camera.width * 100).toInt() }, { "$it%" }) {
                val a = draft.value.calibration.area
                draft.cameraSize(a.width * it / 100f, a.height * draft.value.camera.height)
            }
            if(selected == EditorDraft.Control.CAMERA && customCameraRegion()) slider("Alto", 4, 95, { (draft.value.camera.height * 100).toInt() }, { "$it%" }) {
                val a = draft.value.calibration.area
                draft.cameraSize(a.width * draft.value.camera.width, a.height * it / 100f)
            }
            if(selected == EditorDraft.Control.CAMERA) slider("Sensibilidad X", 1, 200, { (draft.value.camera.sensitivityX * 20).toInt() }, { String.format(Locale.ROOT, "%.2f", it / 20f) }) {
                draft.replace(draft.value.copy(camera = draft.value.camera.copy(sensitivityX = it / 20f)))
            }
            if(selected == EditorDraft.Control.CAMERA) slider("Sensibilidad Y", 1, 200, { (draft.value.camera.sensitivityY * 20).toInt() }, { String.format(Locale.ROOT, "%.2f", it / 20f) }) {
                draft.replace(draft.value.copy(camera = draft.value.camera.copy(sensitivityY = it / 20f)))
            }
        }

        private fun customCameraRegion():Boolean=draft.value.camera.let {
            it.customRegion && !CameraSpec.isGgFreeFirePackage(draft.value.packageName)
        }

        private fun addCameraRegion() {
            if(CameraSpec.isGgFreeFirePackage(draft.value.packageName)) return
            var refreshing=false
            val custom=CheckBox(context).apply {
                text="Usar región personalizada";textSize=13f;setTextColor(Color.WHITE)
                buttonTintList=ToggleStyle.checkColors()
                isChecked=draft.value.camera.customRegion
                setOnCheckedChangeListener { view,checked ->
                    if(!refreshing && view.isAttachedToWindow && scroller.visibility==View.VISIBLE && selected==EditorDraft.Control.CAMERA) {
                        draft.replace(draft.value.copy(camera=draft.value.camera.copy(customRegion=checked)))
                        showPanel()
                    }
                }
            }
            refreshers += {
                refreshing=true
                custom.isChecked=draft.value.camera.customRegion
                refreshing=false
            }
            body.addView(SettingHelp.heading(context,custom,"Región personalizada",
                "Permite ajustar ancho y alto del área de cámara en los juegos que utilizan una región. Desactivado utiliza el área de juego."))
        }

        private fun addCameraSuspend() {
            val row=assignmentRow("Suspender")
            val assign=assignmentButton("Tecla o botón para suspender") { pendingBinding="cameraSuspend"; scroller.visibility=View.GONE; requestFocus(); refresh() }
            row.addView(assign,LinearLayout.LayoutParams(dp(76),dp(34)))
            val clear=smallClearButton("Quitar asignación de suspensión") {
                draft.replace(draft.value.copy(camera=draft.value.camera.copy(suspendKey=0,suspendMouseButton=0))); refresh()
            }
            row.addView(clear,LinearLayout.LayoutParams(dp(28),dp(28)).apply { leftMargin=dp(6) })
            refreshers += {
                val code=draft.value.camera.suspendKey
                val mouse=draft.value.camera.suspendMouseButton
                assign.text=when { mouse!=0 -> "M${Integer.numberOfTrailingZeros(mouse)+1}";code!=0 -> keyName(code);else -> "Asignar" }
                clear.isEnabled=code!=0 || mouse!=0;clear.alpha=if(clear.isEnabled) 1f else .35f
            }
            body.addView(row)
        }

        private fun addMacroPanel() {
            val macro=draft.value.macros.getOrNull(draft.selectedMacro) ?: return
            val owner=macro.id
            fun current()=scroller.isAttachedToWindow && scroller.visibility==View.VISIBLE &&
                selected==EditorDraft.Control.MACRO && draft.value.macros.getOrNull(draft.selectedMacro)?.id==owner
            helpLabel("Puntos · ${macro.steps.size}/${GameProfile.MAX_MACRO_STEPS}",
                "Ejecuta estos puntos una vez por pulsación, en orden. La secuencia completa puede durar hasta 30 segundos.")
            for(index in macro.steps.indices) {
                val row=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
                val coordinate=TextView(context).apply {
                    textSize=11f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(180,218,255))
                }
                refreshers += {
                    val step=draft.value.macros.firstOrNull { it.id==owner }?.steps?.getOrNull(index)
                    if(step!=null) {
                        val area=draft.value.calibration.area
                        coordinate.text="${index+1} · X ${area.displayX(step.x).roundToInt()}  Y ${area.displayY(step.y).roundToInt()}"
                    }
                }
                row.addView(coordinate,LinearLayout.LayoutParams(0,dp(40),1f))
                row.addView(button("Ubicar") {
                    if(current()) { pendingBinding=null;pendingMacroPoint=index;scroller.visibility=View.GONE;refresh() }
                },LinearLayout.LayoutParams(dp(62),dp(36)))
                row.addView(smallClearButton("Eliminar punto ${index+1}") {
                    if(current() && draft.macro().steps.size>1) {
                        removeWaitInput(owner,index);draft.removeMacroStep(index);showPanel(false)
                    }
                }.apply { isEnabled=macro.steps.size>1 },LinearLayout.LayoutParams(dp(28),dp(28)).apply { leftMargin=dp(6) })
                body.addView(row)
                addMacroWait(owner,index) { current() }
            }
            body.addView(button("Agregar punto") {
                if(current()) { pendingBinding=null;pendingMacroPoint=-1;scroller.visibility=View.GONE;refresh() }
            }.apply { isEnabled=macro.steps.size<GameProfile.MAX_MACRO_STEPS },LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(42)))
            body.addView(button("Eliminar macro") {
                if(current()) {
                    invalidWaits.keys.removeAll { it.macroId==owner }
                    draft.removeMacro();pendingMacroPoint=null;macroPointsVisible=false
                    if(draft.value.macros.isEmpty()) { selected=EditorDraft.Control.ACTION;closePanel() } else showPanel(false)
                }
            },LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(42)).apply { topMargin=dp(6) })
        }

        private fun removeWaitInput(owner:String,removed:Int) {
            val entries=invalidWaits.filterKeys { it.macroId==owner }.toList()
            invalidWaits.keys.removeAll { it.macroId==owner }
            for((key,value) in entries) if(key.step!=removed) {
                invalidWaits[key.copy(step=if(key.step>removed) key.step-1 else key.step)]=value
            }
        }
        private fun waitValue(raw:String,minimum:Int,maximum:Int):Int? = if(raw.isNotEmpty() && raw.all { it in '0'..'9' })
            raw.toIntOrNull()?.takeIf { it in minimum..maximum } else null

        private fun addMacroWait(owner:String,index:Int,current:()->Boolean) {
            val key=WaitInput(owner,index)
            addMillisecondsField("Espera","Espera punto ${index+1}, milisegundos",0,GameProfile.MAX_MACRO_WAIT_MS,
                current,
                { draft.value.macros.firstOrNull { it.id==owner }?.steps?.getOrNull(index)?.waitMs ?: 0 },
                { invalidWaits[key] },
                { raw -> if(raw==null) invalidWaits.remove(key) else invalidWaits[key]=raw },
                { value -> draft.macroWait(index,value) },
                { field -> waitFields[key]=field })
        }
        private fun addRepeatInterval() {
            val index=draft.selectedAction
            addMillisecondsField("Intervalo","Intervalo de repetición, milisegundos",50,5000,
                { scroller.isAttachedToWindow && scroller.visibility==View.VISIBLE && selected==EditorDraft.Control.ACTION &&
                    draft.selectedAction==index && draft.value.actions.getOrNull(index)?.behavior=="repeat" },
                { draft.value.actions.getOrNull(index)?.repeatIntervalMs ?: 100 },
                { invalidIntervals[index] },
                { raw -> if(raw==null) invalidIntervals.remove(index) else invalidIntervals[index]=raw },
                { value -> draft.replaceAction(draft.action().copy(repeatIntervalMs=value)) },
                { field -> intervalFields[index]=field })
        }
        private fun addMillisecondsField(title:String,description:String,minimum:Int,maximum:Int,
                                         current:()->Boolean,read:()->Int,pending:()->String?,
                                         editPending:(String?)->Unit,write:(Int)->Unit,remember:(EditText)->Unit) {
            val row=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
            row.addView(TextView(context).apply { text=title;textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL },LinearLayout.LayoutParams(0,dp(40),1f))
            val explanation=if(title=="Espera") "Pausa después de este punto antes de continuar con el siguiente."
                else "Mantén la tecla o botón para repetir la acción con este intervalo."
            row.addView(SettingHelp.button(context,title,explanation),LinearLayout.LayoutParams(dp(24),dp(24)).apply { rightMargin=dp(6) })
            val input=EditText(context).apply {
                inputType=InputType.TYPE_CLASS_NUMBER
                // Keep invalid physical/pasted text visible instead of silently stripping it.
                keyListener=TextKeyListener.getInstance();setRawInputType(InputType.TYPE_CLASS_NUMBER)
                showSoftInputOnFocus=false;imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isSingleLine=true;textSize=13f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)
                setPadding(dp(6),dp(4),dp(6),dp(4));setSelectAllOnFocus(true)
                isLongClickable=false;customSelectionActionModeCallback=noTextPopup;customInsertionActionModeCallback=noTextPopup
                importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                contentDescription=description;setText(pending() ?: read().toString())
            }
            remember(input)
            row.addView(input,LinearLayout.LayoutParams(dp(76),dp(34)))
            row.addView(TextView(context).apply { text="ms";textSize=12f;setTextColor(Color.rgb(180,218,255));gravity=Gravity.CENTER },LinearLayout.LayoutParams(dp(26),dp(34)))
            body.addView(row)
            val invalid=TextView(context).apply { text="Introduce ${minimum}–${maximum} ms";textSize=11f;setTextColor(Color.rgb(255,189,88)) }
            body.addView(invalid)
            val pad=FrameLayout(context).apply { visibility=View.GONE }
            body.addView(pad,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
            var syncing=false
            fun accepted()=waitValue(input.text.toString(),minimum,maximum)!=null
            fun finish() { if(accepted()) { pad.visibility=View.GONE;activeNumberPad=null;requestFocus() } }
            fun showPad() {
                if(!current() || !input.isAttachedToWindow) return
                if(pad.childCount==0) pad.addView(numberPad(input) { finish() })
                activeNumberPad?.takeIf { it!==pad }?.visibility=View.GONE
                activeNumberPad=pad;pad.visibility=View.VISIBLE
                pad.post { if(pad.isAttachedToWindow && pad.visibility==View.VISIBLE) pad.requestRectangleOnScreen(Rect(0,0,pad.width,pad.height),false) }
            }
            input.setOnClickListener { showPad() }
            input.setOnEditorActionListener { _,action,event ->
                if(action==EditorInfo.IME_ACTION_DONE || event?.keyCode==KeyEvent.KEYCODE_ENTER || event?.keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    finish();true
                } else false
            }
            input.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=Unit
                override fun afterTextChanged(text:Editable?) {
                    if(syncing || !current() || !input.isAttachedToWindow) return
                    val raw=text?.toString().orEmpty();val value=waitValue(raw,minimum,maximum)
                    if(value==null) editPending(raw) else { editPending(null);write(value) }
                    refresh()
                }
            })
            refreshers += {
                val raw=pending() ?: read().toString()
                if(!input.hasFocus() && input.text.toString()!=raw) {
                    syncing=true;input.setText(raw);syncing=false
                }
                val valid=accepted()
                invalid.visibility=if(valid) View.GONE else View.VISIBLE
                input.background=gradient(Color.rgb(25,49,88),Color.rgb(13,30,57),8,
                    if(valid) Color.rgb(74,122,186) else Color.rgb(255,189,88))
            }
        }
        private fun numberPad(input:EditText,done:()->Unit):View = GridLayout(context).apply {
            columnCount=3;rowCount=4
            for(label in listOf("1","2","3","4","5","6","7","8","9","⌫","0","✓")) {
                addView(button(label) {
                    if(!input.isAttachedToWindow) return@button
                    if(label=="✓") { done();return@button }
                    input.requestFocus()
                    val start=minOf(input.selectionStart,input.selectionEnd).coerceAtLeast(0)
                    val end=maxOf(input.selectionStart,input.selectionEnd).coerceAtLeast(start)
                    if(label=="⌫") {
                        if(end>start) input.text.delete(start,end) else if(start>0) input.text.delete(start-1,start)
                    } else input.text.replace(start,end,label)
                }.apply {
                    isFocusable=false;isFocusableInTouchMode=false;textSize=15f
                    contentDescription=when(label) { "⌫" -> "Borrar milisegundo";"✓" -> "Aceptar espera";else -> "Tecla $label" }
                },GridLayout.LayoutParams().apply {
                    width=0;height=dp(38);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f)
                    setMargins(dp(2),dp(2),dp(2),dp(2))
                })
            }
        }

        private fun addCameraFrequency() {
            val savedRate=draft.value.camera.updateRateHz
            val choices=CameraSpec.CAPTURE_RATE_CHOICES_HZ
            // Keep an old saved value as an exact stop until the user chooses a new one.
            val rates=(if(savedRate in choices) choices else
                (choices.filter { it!=0 }+savedRate).distinct().sorted()+0).toIntArray()
            val heading=TextView(context).apply { textSize=12f; setTextColor(Color.WHITE); setPadding(0,dp(8),0,0) }
            body.addView(SettingHelp.heading(context,heading,"Frecuencia de captura",
                "Tope de movimientos entregados: 100 Hz ≈ 10 ms; 125 ≈ 8 ms; 250 ≈ 4 ms; 500 ≈ 2 ms; 1000 ≈ 1 ms. Conserva las muestras; clics y teclas inmediatos. Libre no impone un límite de software ni acelera un mouse lento."))
            val frequency=SeekBar(context).apply {
                min=0; max=rates.lastIndex; progress=rates.indexOf(draft.value.camera.updateRateHz).coerceAtLeast(0)
                contentDescription="Frecuencia de captura del mouse"
                EditorSeekBarStyle.apply(this)
                tickMark=GradientDrawable().apply { shape=GradientDrawable.OVAL; setSize(dp(3),dp(3)); setColor(Color.rgb(91,125,170)) }
                splitTrack=false
            }
            frequency.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar:SeekBar?)=Unit
                override fun onStopTrackingTouch(seekBar:SeekBar?)=Unit
                override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {
                    if(!fromUser || !frequency.isAttachedToWindow || scroller.visibility!=View.VISIBLE || selected!=EditorDraft.Control.CAMERA) return
                    draft.cameraUpdateRate(rates[progress]); refresh()
                }
            })
            body.addView(frequency,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(36)))
            val stops=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
            val captions=rates.mapIndexed { index,rate ->
                TextView(context).apply {
                    text=if(rate==0) "Libre" else rate.toString(); textSize=10f; gravity=Gravity.CENTER; setPadding(0,0,0,dp(6))
                    contentDescription=if(rate==0) "Frecuencia libre" else "$rate hercios"
                    isClickable=true
                    setOnClickListener {
                        if(isAttachedToWindow && scroller.visibility==View.VISIBLE && selected==EditorDraft.Control.CAMERA) {
                            draft.cameraUpdateRate(rates[index]); refresh()
                        }
                    }
                    stops.addView(this,LinearLayout.LayoutParams(0,dp(32),1f))
                }
            }
            body.addView(stops)
            refreshers += {
                val rate=draft.value.camera.updateRateHz
                val index=rates.indexOf(rate).coerceAtLeast(0)
                heading.text="Captura · ${if(rate==0) "Libre" else "$rate Hz"}${if(rate !in choices) " · guardado" else ""}"
                if(frequency.progress!=index) frequency.progress=index
                frequency.stateDescription=if(rate==0) "Libre" else "$rate Hz"
                captions.forEachIndexed { position,caption ->
                    caption.setTextColor(if(position==index) Color.rgb(183,220,255) else Color.rgb(144,168,204))
                    caption.typeface=if(position==index) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                }
            }
        }

        private fun isFireAction() = draft.action().enabled && draft.action().behavior=="tap" && draft.action().triggerKind=="mouse" && draft.action().code==1

        private fun addPrecisionAim() {
            val actionIndex=draft.selectedAction
            var refreshing=false
            val precision=CheckBox(context).apply {
                text="Mira precisa"; textSize=13f; setTextColor(Color.WHITE)
                buttonTintList=ToggleStyle.checkColors()
                isChecked=draft.value.camera.precisionAim
                setOnCheckedChangeListener { view,checked ->
                    if(!refreshing && view.isAttachedToWindow && scroller.visibility==View.VISIBLE &&
                        selected==EditorDraft.Control.ACTION && draft.selectedAction==actionIndex && isFireAction()) {
                        draft.replace(draft.value.copy(camera=draft.value.camera.copy(precisionAim=checked)))
                        refresh()
                    }
                }
            }
            refreshers += {
                refreshing=true
                precision.isChecked=draft.value.camera.precisionAim
                refreshing=false
            }
            body.addView(SettingHelp.heading(context,precision,"Mira precisa",
                "Reduce de forma sostenida los movimientos horizontales finos mientras mantienes M1. Usa la velocidad de las muestras originales; conserva el movimiento vertical y los giros rápidos."))
        }

        private fun addFireCadence() {
            val actionIndex=draft.selectedAction
            var refreshing=false
            val enabled=Switch(context).apply {
                ToggleStyle.apply(this)
                text="Opciones de cadencia";textSize=13f;setTextColor(Color.WHITE)
                isChecked=draft.action().cadenceEnabled
                setOnCheckedChangeListener { view,checked ->
                    if(!refreshing && view.isAttachedToWindow && scroller.visibility==View.VISIBLE &&
                        selected==EditorDraft.Control.ACTION && draft.selectedAction==actionIndex && isFireAction()) {
                        draft.setActionCadenceEnabled(checked)
                        showPanel(false)
                    }
                }
            }
            body.addView(SettingHelp.heading(context,enabled,"Opciones de cadencia",
                "Al mantener M1 durante medio segundo, añade un desplazamiento vertical continuo hasta soltar. " +
                "El porcentaje controla la intensidad: 0 es neutro, derecha hacia arriba e izquierda hacia abajo."))
            refreshers += {
                refreshing=true
                enabled.isChecked=draft.action().cadenceEnabled
                refreshing=false
            }
            if(!draft.action().cadenceEnabled) return
            slider("Desplazamiento Y",-100,100,{ draft.action().cadenceYPercent },{ value ->
                when {
                    value>0 -> "+$value% · Arriba"
                    value<0 -> "$value% · Abajo"
                    else -> "0% · Neutro"
                }
            },signed=true) { value ->
                if(selected==EditorDraft.Control.ACTION && draft.selectedAction==actionIndex && isFireAction()) {
                    draft.setActionCadenceYPercent(value)
                }
            }
            body.addView(LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL;layoutDirection=View.LAYOUT_DIRECTION_LTR
                for((index,caption) in listOf("Abajo −100%","0","Arriba +100%").withIndex()) {
                    addView(TextView(context).apply {
                        text=caption;textSize=10f;setTextColor(Color.rgb(166,190,222))
                        gravity=when(index) { 0 -> Gravity.START;1 -> Gravity.CENTER;else -> Gravity.END }
                    },LinearLayout.LayoutParams(0,dp(24),1f))
                }
            })
        }

        private fun addJoystickBindings() {
            label("Direcciones")
            for ((name, field) in listOf("Arriba" to "up", "Abajo" to "down", "Izquierda" to "left", "Derecha" to "right")) {
                val row=assignmentRow(name)
                val bind = assignmentButton("Asignar $name") { pendingBinding = field; requestFocus(); refresh() }
                refreshers += { bind.text = keyName(bindingCode(field)) }
                row.addView(bind,LinearLayout.LayoutParams(dp(76),dp(34)))
                body.addView(row)
            }
        }

        private fun addArea() {
            helpLabel("Área del juego", "Delimita la zona del juego donde se colocan los controles. Cambiarla también cambia su posición en pantalla.")
            slider("Izquierda", 0, 45, { (draft.value.calibration.area.left / draft.value.calibration.screenWidth * 100).toInt() }, { "$it%" }) {
                val c = draft.value.calibration; draft.area(c.area.copy(left = c.screenWidth * it / 100f))
            }
            slider("Arriba", 0, 45, { (draft.value.calibration.area.top / draft.value.calibration.screenHeight * 100).toInt() }, { "$it%" }) {
                val c = draft.value.calibration; draft.area(c.area.copy(top = c.screenHeight * it / 100f))
            }
            slider("Derecha", 0, 45, { ((draft.value.calibration.screenWidth - draft.value.calibration.area.right) / draft.value.calibration.screenWidth * 100).toInt() }, { "$it%" }) {
                val c = draft.value.calibration; draft.area(c.area.copy(right = c.screenWidth * (1f - it / 100f)))
            }
            slider("Abajo", 0, 45, { ((draft.value.calibration.screenHeight - draft.value.calibration.area.bottom) / draft.value.calibration.screenHeight * 100).toInt() }, { "$it%" }) {
                val c = draft.value.calibration; draft.area(c.area.copy(bottom = c.screenHeight * (1f - it / 100f)))
            }
        }

        private fun select(control: EditorDraft.Control) {
            if(selected!=control) { cameraRegionVisible=false; macroPointsVisible=false; scroller.visibility=View.GONE }
            pendingBinding=null; pendingMacroPoint=null; selected=control
            if(control==EditorDraft.Control.MACRO && scroller.visibility==View.VISIBLE) showPanel(false) else refresh()
        }
        private fun refreshDrag() {
            canvas.postInvalidateOnAnimation()
            // Drag samples only update the drawing when settings are collapsed. When open,
            // refresh labels/seek bars at most once per frame instead of for every input sample.
            if (scroller.visibility == View.VISIBLE && !dragRefreshPending) {
                dragRefreshPending = true
                postOnAnimation(dragPanelRefresh)
            }
        }
        override fun onDetachedFromWindow() {
            removeCallbacks(dragPanelRefresh)
            dragRefreshPending = false
            super.onDetachedFromWindow()
        }
        private fun refresh() {
            invalidIntervals.keys.removeAll { draft.value.actions.getOrNull(it)?.behavior!="repeat" }
            val bindingName=when(pendingBinding) { "cameraToggle" -> "c\u00e1mara / cursor"; "cameraSuspend" -> "suspender"; "macro" -> "la macro"; "action" -> "acci\u00f3n"; "stop" -> "detener"; "edit" -> "abrir mapeo"; else -> pendingBinding }
            hint.text=when { pendingMacroPoint!=null -> if(pendingMacroPoint!!<0) "Coloca el nuevo punto." else "Coloca el punto ${pendingMacroPoint!!+1}."
                pendingPlacement!=null -> when {
                    pendingPlacement==EditorDraft.Control.MACRO -> "Coloca Macro y pulsa una tecla."
                    actionPlacement=="repeat" -> "Coloca Repetir y pulsa una tecla."
                    actionPlacement=="cursorToggle" -> "Coloca Mapa/Mochila y pulsa una tecla."
                    firePlacement -> "Coloca Disparo (M1)."; aimPlacement -> "Coloca Apuntar (M2)."
                    else -> "Coloca la acción y pulsa una tecla."
                }
                pendingBinding=="cameraSuspend" -> "Pulsa una tecla o un botón del mouse para suspender."
                pendingBinding!=null -> "Pulsa una tecla para $bindingName."
                invalidWaits.isNotEmpty() || invalidIntervals.isNotEmpty() -> "Corrige el campo marcado antes de guardar."
                else -> "" }
            saveButton?.alpha=if(invalidWaits.isEmpty() && invalidIntervals.isEmpty()) 1f else .55f
            hint.visibility=if(hint.text.isEmpty()) View.GONE else View.VISIBLE
            instruction.text = when (selected) {
                EditorDraft.Control.JOYSTICK -> "Joystick"
                EditorDraft.Control.CAMERA -> "Cámara"
                EditorDraft.Control.ACTION -> when {
                    isFireAction() -> "Disparo · M1"
                    draft.action().behavior=="repeat" -> "Repetir"
                    draft.action().behavior=="cursorToggle" -> "Mapa/Mochila"
                    else -> "Acción"
                }
                EditorDraft.Control.MACRO -> draft.value.macros.getOrNull(draft.selectedMacro)?.name ?: "Macro"
                EditorDraft.Control.AREA -> "Área del juego"
            }
            val selectedTool=when(selected) {
                EditorDraft.Control.JOYSTICK -> "WASD"
                EditorDraft.Control.CAMERA -> "Cámara"
                EditorDraft.Control.AREA -> "Área"
                EditorDraft.Control.MACRO -> "Macro"
                EditorDraft.Control.ACTION -> when {
                    pendingPlacement!=null && actionPlacement=="repeat" -> "Repetir"
                    pendingPlacement!=null && actionPlacement=="cursorToggle" -> "Mapa/Mochila"
                    pendingPlacement!=null && aimPlacement -> "Apuntar"
                    pendingPlacement!=null && firePlacement -> "Disparo"
                    draft.action().behavior=="repeat" -> "Repetir"
                    draft.action().behavior=="cursorToggle" -> "Mapa/Mochila"
                    draft.action().triggerKind=="mouse" && draft.action().code==1 -> "Disparo"
                    draft.action().triggerKind=="mouse" && draft.action().code==2 -> "Apuntar"
                    else -> "Acción"
                }
            }
            if(highlightedTool!=selectedTool) {
                highlightedTool=selectedTool
                for((name,cell) in toolCells) {
                    cell.isSelected=name==selectedTool
                    cell.background=toolBackground(name==selectedTool)
                }
            }
            if(scroller.visibility==View.VISIBLE) for (update in refreshers) update()
            canvas.invalidate()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val fromMouse=event.isFromSource(InputDevice.SOURCE_MOUSE)
            if(capturedKey==event.keyCode && event.action==KeyEvent.ACTION_UP &&
                (capturedKeyMouseDevice==null || (fromMouse && capturedKeyMouseDevice==event.deviceId))) {
                capturedKey=-1;capturedKeyMouseDevice=null
                if(fromMouse) releaseCapturedSuspendMouse(event.deviceId,event.eventTime)
                return true
            }
            // Android synthesizes BACK/FORWARD keys as well as mouse-button events.
            // Deduplicate only the physical press captured by this binding operation.
            val sideButton=if(fromMouse) when(event.keyCode) {
                KeyEvent.KEYCODE_BACK -> MotionEvent.BUTTON_BACK
                KeyEvent.KEYCODE_FORWARD -> MotionEvent.BUTTON_FORWARD
                else -> 0
            } else 0
            if(sideButton!=0 && capturedSuspendMouseDevice==event.deviceId &&
                (capturedSuspendMouseButton==sideButton || (event.action==KeyEvent.ACTION_UP &&
                    lastSuspendMouseButton==sideButton && lastSuspendMouseReleaseTime==event.eventTime))) {
                if(event.action==KeyEvent.ACTION_DOWN) { capturedKey=event.keyCode;capturedKeyMouseDevice=event.deviceId }
                if(event.action==KeyEvent.ACTION_UP) releaseCapturedSuspendMouse(event.deviceId,event.eventTime)
                return true
            }
            val binding = pendingBinding
            if (binding != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    capturedKey = event.keyCode
                    capturedKeyMouseDevice=if(fromMouse) event.deviceId else null
                    if(binding=="cameraSuspend" && sideButton!=0) {
                        bindSuspendMouse(sideButton,event.deviceId)
                        return true
                    }
                    pendingBinding = null
                    try { draft.bindKey(binding,event.keyCode) }
                    catch(e:IllegalArgumentException) { showPanel();error.text=e.message;refresh();return true }
                    error.text = ""
                    if(binding=="macro" || binding=="cameraSuspend") showPanel() else refresh()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) cancel(); return true }
            return super.dispatchKeyEvent(event)
        }

        private fun bindSuspendMouse(button:Int,deviceId:Int) {
            capturedSuspendMouseButton=button;capturedSuspendMouseDevice=deviceId
            lastSuspendMouseButton=button;lastSuspendMouseReleaseTime=Long.MIN_VALUE
            pendingBinding=null
            try { draft.bindCameraSuspendMouse(button);error.text="" }
            catch(failure:IllegalArgumentException) { error.text=failure.message }
            showPanel();refresh()
        }
        private fun releaseCapturedSuspendMouse(deviceId:Int,eventTime:Long) {
            if(capturedSuspendMouseDevice!=deviceId) return
            capturedSuspendMouseButton=0;lastSuspendMouseReleaseTime=eventTime
        }
        override fun dispatchGenericMotionEvent(event:MotionEvent):Boolean {
            trackEditorPointer(event)
            if(event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                val button=event.actionButton
                if(event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS && button in intArrayOf(1,2,4,8,16)) {
                    if(pendingBinding=="cameraSuspend") { bindSuspendMouse(button,event.deviceId);return true }
                    if(capturedSuspendMouseDevice==event.deviceId && capturedSuspendMouseButton==button) return true
                }
                if(event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE && capturedSuspendMouseDevice==event.deviceId &&
                    (capturedSuspendMouseButton==button || (lastSuspendMouseButton==button && lastSuspendMouseReleaseTime==event.eventTime))) {
                    releaseCapturedSuspendMouse(event.deviceId,event.eventTime);return true
                }
            }
            return super.dispatchGenericMotionEvent(event)
        }
        override fun dispatchTouchEvent(event:MotionEvent):Boolean {
            trackEditorPointer(event)
            if(capturedSuspendMouseTouch && event.actionMasked==MotionEvent.ACTION_CANCEL) {
                capturedSuspendMouseTouch=false;releaseCapturedSuspendMouse(capturedSuspendMouseDevice,event.eventTime)
                return true
            }
            if(event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                if(event.actionMasked==MotionEvent.ACTION_DOWN && pendingBinding=="cameraSuspend") {
                    val button=intArrayOf(1,2,4,8,16).firstOrNull { event.buttonState and it!=0 }
                    if(button!=null) {
                        capturedSuspendMouseTouch=true;bindSuspendMouse(button,event.deviceId);return true
                    }
                }
                if(event.actionMasked==MotionEvent.ACTION_DOWN && capturedSuspendMouseDevice==event.deviceId &&
                    capturedSuspendMouseButton!=0 && event.buttonState and capturedSuspendMouseButton!=0) {
                    // Some dispatchers deliver BUTTON_PRESS before the touch DOWN.
                    capturedSuspendMouseTouch=true;return true
                }
                if(capturedSuspendMouseTouch && capturedSuspendMouseDevice==event.deviceId) {
                    if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                        capturedSuspendMouseTouch=false;releaseCapturedSuspendMouse(event.deviceId,event.eventTime)
                    }
                    return true
                }
            }
            return super.dispatchTouchEvent(event)
        }

        private fun bindingCode(field: String): Int = when (field) {
            "up" -> draft.value.joystick.upKey
            "down" -> draft.value.joystick.downKey
            "left" -> draft.value.joystick.leftKey
            "cameraToggle" -> draft.value.shortcuts.cameraToggle
            "cameraSuspend" -> draft.value.camera.suspendKey
            "stop" -> draft.value.shortcuts.stop
            "edit" -> draft.value.shortcuts.edit
            else -> draft.value.joystick.rightKey
        }
        private fun label(text: String) { body.addView(TextView(context).apply { this.text = text; setTextColor(Color.rgb(166,190,222)); textSize = 11f; setPadding(0, dp(6), 0, dp(5)) }) }
        private fun helpLabel(title:String,explanation:String) {
            val caption=TextView(context).apply {
                text=title;setTextColor(Color.rgb(166,190,222));textSize=11f;setPadding(0,dp(6),0,dp(5))
            }
            body.addView(SettingHelp.heading(context,caption,title,explanation))
        }
        private fun assignmentRow(name:String)=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(40)
            addView(TextView(context).apply {
                text=name;textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL
                setPadding(0,0,dp(8),0)
            },LinearLayout.LayoutParams(0,dp(40),1f))
            if(name=="Suspender") addView(SettingHelp.button(context,name,
                "Mantén esta tecla o botón para usar el cursor temporalmente. Al regresar a la cámara empieza un contacto nuevo y se limpia el historial de Mira precisa. Se liberan las acciones mantenidas; no cambia la sensibilidad guardada."),
                LinearLayout.LayoutParams(dp(24),dp(24)).apply { rightMargin=dp(6) })
        }
        private fun assignmentButton(description:String,clicked:(View)->Unit)=button("",clicked).apply {
            contentDescription=description;textSize=12f;typeface=android.graphics.Typeface.DEFAULT_BOLD
            background=gradient(Color.rgb(25,49,88),Color.rgb(13,30,57),8,Color.rgb(74,122,186))
        }
        private fun smallClearButton(description:String,clicked:(View)->Unit)=ImageButton(context).apply {
            setImageResource(R.drawable.mm_close);imageTintList=null;scaleType=ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7),dp(7),dp(7),dp(7));background=gradient(Color.rgb(31,53,88),Color.rgb(18,34,61),8)
            contentDescription=description;setOnClickListener(clicked)
        }
        private fun button(text: String, clicked: (View) -> Unit): Button = Button(context).apply {
            this.text=text; textSize=11f; isAllCaps=false; setPadding(dp(4),0,dp(4),0)
            minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;gravity=Gravity.CENTER;includeFontPadding=false
            setTextColor(Color.rgb(232,242,255)); background=gradient(Color.rgb(31,53,88),Color.rgb(18,34,61),10)
            stateListAnimator=null; setOnClickListener(clicked)
        }
        private fun slider(name: String, minimum: Int, maximum: Int, read: () -> Int, format: (Int) -> String, signed:Boolean=false, change: (Int) -> Unit) {
            val text = TextView(context).apply { setTextColor(Color.WHITE); textSize = 12f }
            val slider = SeekBar(context).apply {
                min=minimum; max=maximum; progress=read().coerceIn(minimum,maximum)
                if(signed) layoutDirection=View.LAYOUT_DIRECTION_LTR
                EditorSeekBarStyle.apply(this)
            }
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { change(progress); refresh() } }
            })
            refreshers += { val value = read().coerceIn(minimum, maximum); text.text = "$name: ${format(value)}"; if (slider.progress != value) slider.progress = value }
            val explanation=when(name) {
                "Radio" -> "Recorrido del joystick desde su centro. Un radio mayor desplaza más el control al mantener una dirección."
                "Ancho","Alto" -> "Tamaño del área de la cámara por región. El movimiento se mantiene dentro de sus bordes."
                "Sensibilidad X" -> "Distancia horizontal que gira la cámara por cada movimiento del mouse."
                "Sensibilidad Y" -> "Distancia vertical que gira la cámara por cada movimiento del mouse."
                "Desplazamiento Y" -> "Tras medio segundo manteniendo M1: positivo hacia arriba, negativo hacia abajo y 0 sin desplazamiento. La intensidad máxima recorre el 20 % del alto del área del juego por segundo; se detiene al soltar M1."
                else -> "Margen de este borde en el área del juego. Los controles usan el espacio que queda dentro."
            }
            body.addView(SettingHelp.heading(context,text,name,explanation))
            body.addView(slider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(34)))
        }
        private fun dp(value: Int) = (value * density + .5f).toInt()
        private fun keyName(code: Int) = editorKeyLabel(code)
    }

    private class ControlsCanvas(
        context: Context,
        private val draft: EditorDraft,
        private val selected: () -> EditorDraft.Control,
        private val select: (EditorDraft.Control) -> Unit,
        private val changed: () -> Unit,
        private val bind: (EditorDraft.Control) -> Unit,
        private val placing: () -> EditorDraft.Control?,
        private val place: (EditorDraft.Control,Float,Float) -> Unit,
        private val properties: (EditorDraft.Control) -> Unit,
        private val cameraRegion: () -> Boolean,
        private val placingMacroPoint: () -> Int?,
        private val placeMacroPoint: (Int,Float,Float) -> Unit,
        private val showMacroSteps: () -> Boolean,
        private val remove: (EditorDraft.Control) -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * density; color = Color.WHITE; typeface=android.graphics.Typeface.DEFAULT_BOLD }
        private val circleGradientMatrix=Matrix()
        private val circleGradients=arrayOf(
            LinearGradient(-1f,-1f,1f,1f,intArrayOf(Color.argb(94,58,116,197),Color.argb(70,9,28,61)),null,Shader.TileMode.CLAMP),
            LinearGradient(-1f,-1f,1f,1f,intArrayOf(Color.argb(164,64,139,243),Color.argb(120,10,36,88)),null,Shader.TileMode.CLAMP),
        )
        private val origin = IntArray(2)
        private var dragging: EditorDraft.Control? = null
        private var draggingMacroStep: Int? = null
        private var deleteGesture=false
        private val deleteGlyph=context.getDrawable(R.drawable.mm_delete)
        private enum class ResizeTarget { NONE, JOYSTICK_RADIUS, MARKER }
        private var resizing = ResizeTarget.NONE
        private var areaEdge = 0
        private var offsetX = 0f
        private var offsetY = 0f
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragMoved = false
        private var tapBinding: EditorDraft.Control? = null
        private var secondaryGesture=false
        private val touchSlop=android.view.ViewConfiguration.get(context).scaledTouchSlop

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(origin)
            canvas.save()
            canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
            val p = draft.value; val a = p.calibration.area
            val area = RectF(a.left, a.top, a.right, a.bottom)
            line.color = Color.rgb(139, 194, 255)
            line.strokeWidth = (if (selected() == EditorDraft.Control.AREA) 3 else 1) * density
            if(selected()==EditorDraft.Control.AREA) {
                canvas.drawRect(area,line)
                canvas.drawText("\u00c1rea: ${a.width.toInt()} x ${a.height.toInt()}",a.left+8*density,a.top+20*density,text)
            }
            val jx = a.left + p.joystick.x * a.width; val jy = a.top + p.joystick.y * a.height
            val radius = p.joystick.radius * min(a.width, a.height)
            if(p.joystick.enabled) {
                shapeStyle(EditorDraft.Control.JOYSTICK, Color.rgb(106, 184, 255))
                circleSurface(canvas,jx,jy,radius,selected()==EditorDraft.Control.JOYSTICK)
                canvas.drawCircle(jx, jy, radius, line)
                val alpha=line.alpha; line.alpha=80
                canvas.drawLine(jx-radius*.75f,jy,jx+radius*.75f,jy,line)
                canvas.drawLine(jx,jy-radius*.75f,jx,jy+radius*.75f,line)
                line.alpha=alpha
                circleLabel(canvas,"WASD",jx,jy,radius)
                if(selected()==EditorDraft.Control.JOYSTICK) handle(canvas,jx+radius,jy+radius,line.color)
            }
            val cx = a.left + p.camera.x * a.width; val cy = a.top + p.camera.y * a.height
            val cw = p.camera.width * a.width; val ch = p.camera.height * a.height
            if(p.camera.enabled) {
                shapeStyle(EditorDraft.Control.CAMERA, Color.rgb(155, 201, 255))
                if(cameraRegion() && p.camera.customRegion && !CameraSpec.isGgFreeFirePackage(p.packageName)) canvas.drawRect(cx-cw/2,cy-ch/2,cx+cw/2,cy+ch/2,line)
                val cameraRadius=p.camera.markerSizeDp*density/2
                circleSurface(canvas,cx,cy,cameraRadius,selected()==EditorDraft.Control.CAMERA)
                canvas.drawCircle(cx,cy,cameraRadius,line);targetGlyph(canvas,cx,cy,cameraRadius,line.color)
                circleLabel(canvas,keyLabel(p.shortcuts.cameraToggle),cx,cy,cameraRadius)
                if(selected()==EditorDraft.Control.CAMERA) handle(canvas,cx+cameraRadius,cy+cameraRadius,line.color)
            }
            for((index,action) in p.actions.withIndex()) {
                if(!action.enabled) continue
                val ax=a.displayX(action.x); val ay=a.displayY(action.y)
                val active=selected()==EditorDraft.Control.ACTION && draft.selectedAction==index
                val markerRadius=action.markerSizeDp*density/2
                line.color=when { action.triggerKind=="mouse" && action.code==1 -> Color.rgb(108,177,255); action.triggerKind=="mouse" && action.code==2 -> Color.rgb(133,211,255); else -> Color.rgb(183,206,255) }
                line.strokeWidth=(if(active) 2.4f else 1.5f)*density
                circleSurface(canvas,ax,ay,markerRadius,active)
                canvas.drawCircle(ax,ay,markerRadius,line)
                if(action.triggerKind=="mouse" && action.code in listOf(1,2)) targetGlyph(canvas,ax,ay,markerRadius,line.color)
                circleLabel(canvas,if(action.triggerKind=="key") keyLabel(action.code) else "M${when(action.code) { 1->1;2->2;4->3;8->4;else->5 }}",ax,ay,markerRadius)
                if(active) handle(canvas,ax+markerRadius,ay+markerRadius,line.color)
            }
            for((index,macro) in p.macros.withIndex()) {
                val mx=a.displayX(macro.x);val my=a.displayY(macro.y);val mr=macro.markerSizeDp*density/2
                val active=selected()==EditorDraft.Control.MACRO && draft.selectedMacro==index
                line.color=Color.rgb(172,184,255);line.strokeWidth=(if(active) 2.4f else 1.5f)*density
                circleSurface(canvas,mx,my,mr,active);canvas.drawCircle(mx,my,mr,line)
                if(active && showMacroSteps()) {
                    val old=text.textSize;text.textSize=11*density
                    val caption="Macro · ${macroTrigger(macro)}"
                    canvas.drawText(caption,mx-text.measureText(caption)/2,my-mr-7*density,text);text.textSize=old
                } else circleLabel(canvas,macroTrigger(macro),mx,my,mr)
                if(active) handle(canvas,mx+mr,my+mr,line.color)
            }
            if(selected()==EditorDraft.Control.MACRO && showMacroSteps()) {
                val macro=p.macros.getOrNull(draft.selectedMacro)
                if(macro!=null) {
                    line.color=Color.argb(175,153,188,255);line.strokeWidth=1.5f*density
                    for((index,step) in macro.steps.withIndex()) {
                        val px=a.displayX(step.x);val py=a.displayY(step.y)
                        fill.color=Color.rgb(30,72,145);canvas.drawCircle(px,py,10*density,fill)
                        canvas.drawCircle(px,py,10*density,line)
                        val old=text.textSize;text.textSize=11*density
                        val number="${index+1}";val metrics=text.fontMetrics
                        canvas.drawText(number,px-text.measureText(number)/2,py-(metrics.ascent+metrics.descent)/2,text)
                        text.textSize=9*density
                        val coordinates="X ${px.roundToInt()}  Y ${py.roundToInt()}"
                        val labelX=px+14*density;val labelY=py+4*density
                        val labelBounds=RectF(labelX,py-9*density,labelX+text.measureText(coordinates),py+6*density)
                        val clear=labelBounds.right<=a.right && labelBounds.bottom<=a.bottom && labelBounds.top>=a.top &&
                            macro.steps.withIndex().none { (otherIndex,other) ->
                                otherIndex!=index && a.displayX(other.x) in (labelBounds.left-12*density)..(labelBounds.right+12*density) &&
                                    a.displayY(other.y) in (labelBounds.top-12*density)..(labelBounds.bottom+12*density)
                            }
                        if(clear) canvas.drawText(coordinates,labelX,labelY,text)
                        text.textSize=old
                    }
                    handle(canvas,a.displayX(macro.x)+macro.markerSizeDp*density/2,a.displayY(macro.y)+macro.markerSizeDp*density/2,Color.rgb(172,184,255))
                }
            }
            drawDeleteHandle(canvas)
            canvas.restore()
        }

        private fun selectedDeletePoint():PointF? {
            if(placing()!=null || placingMacroPoint()!=null) return null
            val p=draft.value;val a=p.calibration.area
            val centerAndRadius=when(selected()) {
                EditorDraft.Control.JOYSTICK -> if(p.joystick.enabled) Triple(a.displayX(p.joystick.x),a.displayY(p.joystick.y),p.joystick.radius*min(a.width,a.height)) else null
                EditorDraft.Control.CAMERA -> if(p.camera.enabled) Triple(a.displayX(p.camera.x),a.displayY(p.camera.y),p.camera.markerSizeDp*density/2) else null
                EditorDraft.Control.ACTION -> p.actions.getOrNull(draft.selectedAction)?.takeIf { it.enabled }?.let { Triple(a.displayX(it.x),a.displayY(it.y),it.markerSizeDp*density/2) }
                EditorDraft.Control.MACRO -> p.macros.getOrNull(draft.selectedMacro)?.let { Triple(a.displayX(it.x),a.displayY(it.y),it.markerSizeDp*density/2) }
                EditorDraft.Control.AREA -> null
            } ?: return null
            val half=12*density
            return PointF((centerAndRadius.first+centerAndRadius.third).coerceIn(half,maxOf(half,p.calibration.screenWidth-half)),
                (centerAndRadius.second-centerAndRadius.third).coerceIn(half,maxOf(half,p.calibration.screenHeight-half)))
        }
        private fun drawDeleteHandle(canvas:Canvas) {
            val point=selectedDeletePoint() ?: return
            fill.color=Color.rgb(72,30,55);canvas.drawCircle(point.x,point.y,12*density,fill)
            line.color=Color.rgb(255,154,176);line.strokeWidth=density;canvas.drawCircle(point.x,point.y,12*density,line)
            deleteGlyph?.apply {
                setBounds((point.x-10*density).roundToInt(),(point.y-10*density).roundToInt(),(point.x+10*density).roundToInt(),(point.y+10*density).roundToInt());draw(canvas)
            }
        }
        private fun hitDelete(x:Float,y:Float):Boolean {
            val point=selectedDeletePoint() ?: return false
            return abs(x-point.x)<=12*density && abs(y-point.y)<=12*density
        }

        private fun macroTrigger(macro:MacroSpec):String = if(macro.triggerKind=="key") keyLabel(macro.code)
            else "M${when(macro.code) { 1->1;2->2;4->3;8->4;else->5 }}"
        private fun keyLabel(code:Int):String=editorKeyLabel(code)
        private fun circleLabel(canvas:Canvas,label:String,x:Float,y:Float,radius:Float) {
            val old=text.textSize; text.textSize=min(14*density,radius*.64f)
            val width=text.measureText(label)
            if(width>radius*1.65f) text.textSize*=radius*1.65f/width
            val metrics=text.fontMetrics
            canvas.drawText(label,x-text.measureText(label)/2,y-(metrics.ascent+metrics.descent)/2,text)
            text.textSize=old
        }
        private fun circleSurface(canvas:Canvas,x:Float,y:Float,radius:Float,active:Boolean) {
            val gradient=circleGradients[if(active) 1 else 0]
            circleGradientMatrix.setScale(radius,radius)
            circleGradientMatrix.postTranslate(x,y)
            gradient.setLocalMatrix(circleGradientMatrix)
            fill.color=Color.WHITE
            fill.shader=gradient
            fill.setShadowLayer(2*density,0f,density,Color.argb(60,0,0,0))
            canvas.drawCircle(x,y,radius,fill)
            fill.clearShadowLayer()
            fill.shader=null
            if(active) {
                val oldColor=line.color;val oldWidth=line.strokeWidth
                line.color=Color.argb(100,129,190,255);line.strokeWidth=2*density
                canvas.drawCircle(x,y,radius+3*density,line)
                line.color=oldColor;line.strokeWidth=oldWidth
            }
        }
        private fun targetGlyph(canvas:Canvas,x:Float,y:Float,radius:Float,color:Int) {
            val oldColor=line.color;val oldWidth=line.strokeWidth
            line.color=color;line.alpha=60;line.strokeWidth=density
            canvas.drawCircle(x,y,radius*.62f,line)
            canvas.drawLine(x-radius*.82f,y,x-radius*.45f,y,line)
            canvas.drawLine(x+radius*.45f,y,x+radius*.82f,y,line)
            canvas.drawLine(x,y-radius*.82f,x,y-radius*.45f,line)
            canvas.drawLine(x,y+radius*.45f,x,y+radius*.82f,line)
            line.color=oldColor;line.strokeWidth=oldWidth
        }
        private fun openProperties(event:MotionEvent):Boolean {
            val p=draft.value; val a=p.calibration.area; val x=event.rawX; val y=event.rawY
            val macroIndex=macroAt(x,y)
            if(macroIndex!=null) { draft.selectMacro(macroIndex);properties(EditorDraft.Control.MACRO);return true }
            if(p.camera.enabled && hypot(x-a.displayX(p.camera.x),y-a.displayY(p.camera.y))<=p.camera.markerSizeDp*density/2+5*density) { properties(EditorDraft.Control.CAMERA); return true }
            val index=actionAt(x,y)
            if(index!=null) {
                draft.selectAction(index); properties(EditorDraft.Control.ACTION); return true
            }
            if(p.joystick.enabled && hypot(x-a.displayX(p.joystick.x),y-a.displayY(p.joystick.y))<=p.joystick.radius*min(a.width,a.height)) { properties(EditorDraft.Control.JOYSTICK);return true }
            return false
        }
        private fun actionAt(x:Float,y:Float):Int? {
            val a=draft.value.calibration.area;val actions=draft.value.actions
            return actions.indices.filter { index ->
                actions[index].enabled && hypot(x-a.displayX(actions[index].x),y-a.displayY(actions[index].y))<=actions[index].markerSizeDp*density/2+5*density
            }.minByOrNull { index -> hypot(x-a.displayX(actions[index].x),y-a.displayY(actions[index].y)) }
        }
        private fun macroAt(x:Float,y:Float):Int? {
            val a=draft.value.calibration.area;val macros=draft.value.macros
            return macros.indices.filter { index ->
                hypot(x-a.displayX(macros[index].x),y-a.displayY(macros[index].y))<=macros[index].markerSizeDp*density/2+5*density
            }.minByOrNull { index -> hypot(x-a.displayX(macros[index].x),y-a.displayY(macros[index].y)) }
        }
        private fun macroStepAt(x:Float,y:Float):Int? {
            if(selected()!=EditorDraft.Control.MACRO || !showMacroSteps()) return null
            val macro=draft.value.macros.getOrNull(draft.selectedMacro) ?: return null
            val a=draft.value.calibration.area
            val radius=macro.markerSizeDp*density/2
            if(hypot(x-a.displayX(macro.x)-radius,y-a.displayY(macro.y)-radius)<=12*density) return null
            // The marker remains movable when its first point shares its center.
            if(hypot(x-a.displayX(macro.x),y-a.displayY(macro.y))<=macro.markerSizeDp*density/2+5*density) return null
            return macro.steps.indices.filter { index ->
                val step=macro.steps[index];hypot(x-a.displayX(step.x),y-a.displayY(step.y))<=14*density
            }.minByOrNull { index -> val step=macro.steps[index];hypot(x-a.displayX(step.x),y-a.displayY(step.y)) }
        }
        override fun onGenericMotionEvent(event:MotionEvent):Boolean {
            if(event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS && event.actionButton==MotionEvent.BUTTON_SECONDARY) {
                secondaryGesture=true; tapBinding=null; dragging=null; draggingMacroStep=null; openProperties(event); return true
            }
            if(event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE && event.actionButton==MotionEvent.BUTTON_SECONDARY) return true
            return super.onGenericMotionEvent(event)
        }
        private fun shapeStyle(control: EditorDraft.Control, color: Int) { line.color = color; line.strokeWidth = (if (selected() == control) 3f else 1.5f) * density }
        private fun handle(canvas: Canvas, x: Float, y: Float, color: Int) {
            fill.color=Color.rgb(11,27,54)
            canvas.drawRoundRect(x-7*density,y-7*density,x+7*density,y+7*density,3*density,3*density,fill)
            fill.color=color
            canvas.drawRoundRect(x-4*density,y-4*density,x+4*density,y+4*density,density,density,fill)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.rawX; val y = event.rawY
            if(deleteGesture && event.actionMasked!=MotionEvent.ACTION_DOWN) {
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) deleteGesture=false
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteGesture=false
                    tapBinding=null; resizing=ResizeTarget.NONE;draggingMacroStep=null
                    if(event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                        secondaryGesture=true; dragging=null; openProperties(event); return true
                    }
                    secondaryGesture=false
                    if(hitDelete(x,y)) {
                        deleteGesture=true;dragging=null;tapBinding=null;draggingMacroStep=null
                        remove(selected());changed();return true
                    }
                    placingMacroPoint()?.let { index ->
                        dragging=null;placeMacroPoint(index,x,y);changed();return true
                    }
                    placing()?.let { control ->
                        dragging=null; resizing=ResizeTarget.NONE
                        place(control,x,y); changed()
                        return true
                    }
                    dragStartX = x; dragStartY = y; dragMoved = false
                    val p = draft.value; val a = p.calibration.area
                    val hitStep=macroStepAt(x,y)
                    if(hitStep!=null) {
                        val step=draft.macro().steps[hitStep]
                        dragging=EditorDraft.Control.MACRO;draggingMacroStep=hitStep
                        offsetX=x-a.displayX(step.x);offsetY=y-a.displayY(step.y)
                        select(EditorDraft.Control.MACRO);return true
                    }
                    if (selected() == EditorDraft.Control.AREA) {
                        dragging = EditorDraft.Control.AREA
                        areaEdge = listOf(abs(x - a.left), abs(y - a.top), abs(x - a.right), abs(y - a.bottom)).withIndex().minBy { it.value }.index
                    } else {
                        val jx = a.left + p.joystick.x * a.width; val jy = a.top + p.joystick.y * a.height
                        val radius = p.joystick.radius * min(a.width, a.height)
                        val cx = a.left + p.camera.x * a.width; val cy = a.top + p.camera.y * a.height
                        val cw = p.camera.width * a.width; val ch = p.camera.height * a.height
                        val actions=p.actions
                        val hitAction=actionAt(x,y)
                        val hitMacro=macroAt(x,y)
                        val activeMacro=p.macros.getOrNull(draft.selectedMacro)
                        val mx=activeMacro?.let { a.displayX(it.x) } ?: 0f
                        val my=activeMacro?.let { a.displayY(it.y) } ?: 0f
                        val mr=activeMacro?.let { it.markerSizeDp*density/2 } ?: 0f
                        val index=hitAction ?: draft.selectedAction
                        val fallback=actions.getOrNull(index) ?: draft.action()
                        val ax=a.displayX(fallback.x); val ay=a.displayY(fallback.y)
                        val selectedAction=draft.action()
                        val sx=a.displayX(selectedAction.x);val sy=a.displayY(selectedAction.y)
                        val sr=selectedAction.markerSizeDp*density/2
                        val cr=p.camera.markerSizeDp*density/2
                        val hit = 12 * density
                        resizing = ResizeTarget.NONE
                        dragging = when {
                            p.joystick.enabled && selected()==EditorDraft.Control.JOYSTICK && hypot(x-jx-radius,y-jy-radius)<=hit -> { resizing=ResizeTarget.JOYSTICK_RADIUS;EditorDraft.Control.JOYSTICK }
                            p.camera.enabled && selected()==EditorDraft.Control.CAMERA && hypot(x-cx-cr,y-cy-cr)<=hit -> { resizing=ResizeTarget.MARKER;EditorDraft.Control.CAMERA }
                            selectedAction.enabled && selected()==EditorDraft.Control.ACTION && hypot(x-sx-sr,y-sy-sr)<=hit -> { resizing=ResizeTarget.MARKER;EditorDraft.Control.ACTION }
                            selected()==EditorDraft.Control.MACRO && activeMacro!=null && hypot(x-mx-mr,y-my-mr)<=hit -> { resizing=ResizeTarget.MARKER;EditorDraft.Control.MACRO }
                            hitMacro!=null -> { draft.selectMacro(hitMacro);EditorDraft.Control.MACRO }
                            hitAction!=null -> { draft.selectAction(index); EditorDraft.Control.ACTION }
                            p.joystick.enabled && hypot(x - jx, y - jy) <= radius + hit -> EditorDraft.Control.JOYSTICK
                            p.camera.enabled && (hypot(x-cx,y-cy)<=cr+5*density || (cameraRegion() && p.camera.customRegion && !CameraSpec.isGgFreeFirePackage(p.packageName) && x in (cx-cw/2)..(cx+cw/2) && y in (cy-ch/2)..(cy+ch/2))) -> EditorDraft.Control.CAMERA
                            else -> null
                        }
                        tapBinding=if(resizing==ResizeTarget.NONE && dragging in listOf(EditorDraft.Control.ACTION,EditorDraft.Control.CAMERA,EditorDraft.Control.MACRO)) dragging else null
                        val centre = when (dragging) {
                            EditorDraft.Control.JOYSTICK -> jx to jy
                            EditorDraft.Control.CAMERA -> cx to cy
                            EditorDraft.Control.MACRO -> a.displayX(draft.macro().x) to a.displayY(draft.macro().y)
                            else -> ax to ay
                        }
                        offsetX = if (resizing!=ResizeTarget.NONE) 0f else x - centre.first
                        offsetY = if (resizing!=ResizeTarget.NONE) 0f else y - centre.second
                        dragging?.let(select)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    updateDrag(x, y)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if(secondaryGesture) { secondaryGesture=false; tapBinding=null; dragging=null;draggingMacroStep=null; return true }
                    // UP may carry a final position not present in the last batched MOVE.
                    updateDrag(x, y)
                    if (dragMoved) logCompletedDrag(x, y)
                    else tapBinding?.let(bind)
                    tapBinding=null
                    dragging = null;draggingMacroStep=null; resizing = ResizeTarget.NONE; dragMoved = false
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { tapBinding=null; dragging = null;draggingMacroStep=null; resizing = ResizeTarget.NONE; dragMoved = false; return true }
            }
            return true
        }

        private fun updateDrag(x: Float, y: Float) {
            val control = dragging ?: return
            // A stationary tap selects a control without moving an area edge to that point.
            if (!dragMoved && hypot(x-dragStartX,y-dragStartY) < touchSlop) return
            dragMoved = true
            val p = draft.value; val a = p.calibration.area
            if (control == EditorDraft.Control.AREA) {
                draft.area(when (areaEdge) { 0 -> a.copy(left = x); 1 -> a.copy(top = y); 2 -> a.copy(right = x); else -> a.copy(bottom = y) })
            } else if(draggingMacroStep!=null) {
                draft.moveMacroStep(draggingMacroStep!!,x-offsetX,y-offsetY)
            } else if (resizing==ResizeTarget.JOYSTICK_RADIUS) {
                draft.joystickRadius(maxOf(abs(x-a.displayX(p.joystick.x)),abs(y-a.displayY(p.joystick.y))))
            } else if (resizing==ResizeTarget.MARKER) {
                val centre=when(control) {
                    EditorDraft.Control.CAMERA -> a.displayX(p.camera.x) to a.displayY(p.camera.y)
                    EditorDraft.Control.MACRO -> a.displayX(draft.macro().x) to a.displayY(draft.macro().y)
                    else -> a.displayX(draft.action().x) to a.displayY(draft.action().y)
                }
                draft.markerSize(control,2*maxOf(abs(x-centre.first),abs(y-centre.second))/density)
            } else draft.move(control, x - offsetX, y - offsetY)
            changed()
        }

        private fun logCompletedDrag(x: Float, y: Float) {
            val control = dragging ?: return
            val p = draft.value; val a = p.calibration.area
            val result = when (control) {
                EditorDraft.Control.JOYSTICK -> "center=${a.displayX(p.joystick.x)},${a.displayY(p.joystick.y)} radius=${p.joystick.radius * min(a.width, a.height)}"
                EditorDraft.Control.CAMERA -> {
                    val cx = a.displayX(p.camera.x); val cy = a.displayY(p.camera.y)
                    val rx = p.camera.width * a.width / 2; val ry = p.camera.height * a.height / 2
                    "center=$cx,$cy region=${cx-rx},${cy-ry},${cx+rx},${cy+ry} markerDp=${p.camera.markerSizeDp}"
                }
                EditorDraft.Control.ACTION -> "center=${a.displayX(draft.action().x)},${a.displayY(draft.action().y)} markerDp=${draft.action().markerSizeDp}"
                EditorDraft.Control.MACRO -> "center=${a.displayX(draft.macro().x)},${a.displayY(draft.macro().y)} markerDp=${draft.macro().markerSizeDp} step=$draggingMacroStep"
                EditorDraft.Control.AREA -> "area=${a.left},${a.top},${a.right},${a.bottom}"
            }
            Log.i("MobiMapperEditor", "drag_complete control=$control resize=${resizing!=ResizeTarget.NONE} kind=$resizing start=$dragStartX,$dragStartY final=$x,$y $result")
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
