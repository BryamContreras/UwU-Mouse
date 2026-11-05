package dev.mobimapper.mobimapper.editor

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.mobimapper.mobimapper.R
import dev.mobimapper.mobimapper.game.ProfileMenuItem
import kotlin.math.abs
import kotlin.math.roundToInt

/** Separate small bubble/menu windows; neither takes the game's keyboard focus. */
class ControllerBubble(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val settings = BubbleSettingsStore(service)
    @Volatile private var style = settings.value
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menu: ScrollView? = null
    private var menuParams:WindowManager.LayoutParams?=null
    private var menuPanel:View?=null
    private var label = "Pausada"
    private var profileName = "Perfil local"
    private var editEnabled = true
    private var playEnabled = true
    private var interactionEnabled = true
    private var pointerIntercepted=false
    private var cursorDownTime=0L
    private var playing = false
    private var cursorHint = "F8"
    private var onMenu: () -> Unit = {}
    private var onEdit: () -> Unit = {}
    private var onPlay: () -> Unit = {}
    private var onKeyboard: () -> Unit = {}
    private var onStop: () -> Unit = {}
    private var onCursorReturn: () -> Unit = {}
    private var onVisualSettingsChanged: () -> Unit = {}
    private var profileItems:List<ProfileMenuItem> = emptyList()
    private var onCreateProfile:(String)->Unit = {}
    private var onActivateProfile:(String)->Unit = {}
    private var onProfileKey:(String,Int)->Unit = { _,_ -> }
    private var pendingProfileKeyId:String?=null
    private var profileError:String?=null
    private var renderProfiles:(()->Unit)?=null
    val settingsSnapshot:BubbleSettings get()=style
    val visible: Boolean get() = bubble != null
    val menuVisible: Boolean get() = menu != null
    fun copyMenuBounds(out:Rect):Boolean {
        val p=menuParams ?: return false
        out.set(p.x,p.y,p.x+p.width,p.y+p.height);return true
    }
    fun appearance():Map<String,Any> = style.let { mapOf("schemaVersion" to 2,"sizeDp" to it.sizeDp,
        "opacity" to it.opacity.toDouble(),"x" to it.x.toDouble(),"y" to it.y.toDouble(),
        "dockEdge" to it.dockEdge,"showControls" to it.showControls,"controlsOpacity" to it.controlsOpacity.toDouble()) }

    fun show(label: String, profileName: String, onMenu: () -> Unit, onEdit: () -> Unit,
             onPlay: () -> Unit, onKeyboard: () -> Unit, onStop: () -> Unit, playing: Boolean,
             editEnabled: Boolean, playEnabled: Boolean, interactionEnabled: Boolean, cursorHint: String,
             pointerIntercepted:Boolean=false,onCursorReturn:()->Unit={},onVisualSettingsChanged:()->Unit={},
             profileItems:List<ProfileMenuItem> = emptyList(),onCreateProfile:(String)->Unit={},
             onActivateProfile:(String)->Unit={},onProfileKey:(String,Int)->Unit={_,_->}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        this.label = label; this.profileName = profileName; this.onMenu = onMenu
        this.onEdit = onEdit; this.onPlay = onPlay; this.onKeyboard=onKeyboard; this.onStop = onStop
        this.playing = playing; this.editEnabled = editEnabled; this.playEnabled = playEnabled
        this.interactionEnabled = interactionEnabled; this.cursorHint = cursorHint
        this.pointerIntercepted=pointerIntercepted;this.onCursorReturn=onCursorReturn
        this.onVisualSettingsChanged=onVisualSettingsChanged
        this.profileItems=profileItems;this.onCreateProfile=onCreateProfile
        this.onActivateProfile=onActivateProfile;this.onProfileKey=onProfileKey
        if (bubble == null) createBubble()
        applyBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val view = ImageView(service).apply {
            setImageResource(R.drawable.mm_brand); scaleType=ImageView.ScaleType.FIT_CENTER
            imageTintList=null
            setPadding(dp(4),dp(4),dp(4),dp(4)); isClickable = true
            setOnClickListener { if (interactionEnabled) { if (menuVisible) dismissMenu() else onMenu() } }
        }
        var startX = 0f; var startY = 0f; var originalX = 0; var originalY = 0; var dragged = false
        view.setOnTouchListener { _, event ->
            if (!interactionEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originalX = bubbleParams?.x ?: 0; originalY = bubbleParams?.y ?: 0
                    dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (abs(dx) + abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        closeMenu()
                        val size = screen(); val p = requireNotNull(bubbleParams); val side = dp(style.sizeDp)
                        val position=BubblePlacement.drag(originalX+dx.roundToInt(),originalY+dy.roundToInt(),size.x,size.y,side)
                        p.x=position.x;p.y=position.y
                        windows.updateViewLayout(view, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        val size = screen(); val p = requireNotNull(bubbleParams); val side = dp(style.sizeDp)
                        style=BubblePlacement.settle(style,p.x,p.y,size.x,size.y,side,dp(10))
                        saveStyle();applyBubble()
                    } else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { applyBubble(); true }
                else -> false
            }
        }
        val params = params("MobiMapper · Burbuja")
        val side=dp(style.sizeDp);val size=screen();val position=BubblePlacement.position(style,size.x,size.y,side)
        params.width=side;params.height=side;params.x=position.x;params.y=position.y
        params.flags=params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        bubble = view; bubbleParams = params
        windows.addView(view, params)
    }

    private fun applyBubble() {
        val view = bubble ?: return
        val p = requireNotNull(bubbleParams); val side = dp(style.sizeDp); val size = screen()
        p.width = side; p.height = side
        val position=BubblePlacement.position(style,size.x,size.y,side)
        p.x=position.x;p.y=position.y
        // F8 releases camera input using the existing source/UP transition, without per-sample UI work.
        p.flags = baseFlags() or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (interactionEnabled && !pointerIntercepted) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        view.alpha = style.opacity
        view.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if(playing) intArrayOf(Color.rgb(76,150,248),Color.rgb(25,77,168))
            else intArrayOf(Color.rgb(49,111,203),Color.rgb(22,58,124)),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), if (playing) Color.rgb(177,220,255) else Color.rgb(116,170,239))
        }
        view.contentDescription = if (interactionEnabled) "UwU Mouse · abrir opciones"
            else if(cursorHint.isBlank()) "UwU Mouse · abre la app para pausar" else "UwU Mouse · $cursorHint para abrir opciones"
        view.tooltipText = if(cursorHint.isBlank()) label else "$label · $cursorHint cursor"
        windows.updateViewLayout(view, p)
    }

    /** The coordinator completes its mapping release barrier before opening this menu. */
    fun openMenu() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (bubble == null) return
        closeMenu()
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = gradient(Color.rgb(30,65,119),Color.rgb(10,27,60),14,Color.rgb(100,158,237))
        }
        val header=LinearLayout(service).apply { orientation=LinearLayout.HORIZONTAL }
        val menuGrip=ImageView(service).apply { setImageResource(R.drawable.mm_drag);imageTintList=null;contentDescription="Arrastrar menú" }
        header.addView(menuGrip,LinearLayout.LayoutParams(dp(24),dp(40)))
        val status=text(label,11f).apply { gravity=Gravity.CENTER_VERTICAL; typeface=android.graphics.Typeface.DEFAULT_BOLD }
        header.addView(status,LinearLayout.LayoutParams(0,dp(40),1f))
        header.addView(button("×",true) { dismissMenu() }.apply { contentDescription="Cerrar menú" },LinearLayout.LayoutParams(dp(40),dp(40)))
        panel.addView(header)
        val row=LinearLayout(service).apply { orientation=LinearLayout.HORIZONTAL }
        val detail=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(4),dp(10),dp(4),dp(4)) }
        fun appearancePanel() {
            pendingProfileKeyId=null;renderProfiles=null;profileError=null
            detail.removeAllViews()
            slider(detail,"Tama\u00f1o de burbuja",32,72,style.sizeDp,{ "$it dp" }) { style=style.copy(sizeDp=it); applyBubble() }
            slider(detail,"Opacidad",30,100,(style.opacity*100).roundToInt(),{ "$it%" }) { style=style.copy(opacity=it/100f); applyBubble() }
            val show=Switch(service).apply {
                text="Mostrar controles";textSize=12f;setTextColor(Color.WHITE)
                thumbTintList=ColorStateList.valueOf(Color.rgb(186,224,255))
                isChecked=style.showControls;contentDescription="Mostrar controles en pantalla"
            }
            detail.addView(show,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(44)))
            val opacity=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL }
            detail.addView(opacity)
            slider(opacity,"Opacidad de controles",10,100,(style.controlsOpacity*100).roundToInt(),{ "$it%" }) {
                style=style.copy(controlsOpacity=it/100f);onVisualSettingsChanged()
            }
            fun updateControlsOpacity() { opacity.alpha=if(style.showControls) 1f else .35f
                for(i in 0 until opacity.childCount) opacity.getChildAt(i).isEnabled=style.showControls }
            updateControlsOpacity()
            show.setOnCheckedChangeListener { _,checked ->
                if(checked!=style.showControls) {
                    style=style.copy(showControls=checked);saveStyle();show.isChecked=style.showControls
                    updateControlsOpacity();onVisualSettingsChanged()
                }
            }
            centerMenu()
        }
        fun profilePanel() {
            fun render() {
                detail.removeAllViews()
                detail.addView(text("Perfiles",12f).apply { typeface=android.graphics.Typeface.DEFAULT_BOLD })
                for(item in profileItems) {
                    val row=LinearLayout(service).apply {
                        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                        setPadding(dp(7),dp(3),dp(5),dp(3))
                        background=gradient(if(item.selected) Color.rgb(41,103,187) else Color.rgb(24,57,107),Color.rgb(14,35,73),8)
                    }
                    val name=text(if(item.selected) "✓ ${item.name}" else item.name,12f).apply {
                        maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                        gravity=Gravity.CENTER_VERTICAL;isClickable=true
                        contentDescription="Activar perfil ${item.name}"
                        setOnClickListener {
                            try { onActivateProfile(item.id);closeMenu() }
                            catch(e:Exception) {
                                profileError=e.message ?: "No se pudo activar el perfil";render()
                            }
                        }
                    }
                    row.addView(name,LinearLayout.LayoutParams(0,dp(36),1f))
                    val waiting=pendingProfileKeyId==item.id
                    val key=text(if(waiting) "Pulsa…" else profileKeyLabel(item.switchKey),11f).apply {
                        gravity=Gravity.CENTER;setPadding(dp(4),0,dp(4),0);isClickable=true
                        background=gradient(if(waiting) Color.rgb(68,139,237) else Color.rgb(41,76,131),Color.rgb(22,48,94),7,Color.rgb(100,158,237))
                        contentDescription="Tecla de perfil ${item.name}"
                        setOnClickListener {
                            pendingProfileKeyId=if(waiting) null else item.id;profileError=null;render()
                        }
                    }
                    row.addView(key,LinearLayout.LayoutParams(dp(76),dp(32)).apply { marginStart=dp(6) })
                    row.addView(ImageButton(service).apply {
                        setImageResource(R.drawable.mm_close);imageTintList=null
                        scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(dp(6),dp(6),dp(6),dp(6))
                        minimumWidth=0;minimumHeight=0;background=gradient(Color.rgb(35,69,119),Color.rgb(19,41,80),7)
                        contentDescription="Quitar tecla de perfil ${item.name}";isEnabled=item.switchKey!=0 || waiting
                        alpha=if(isEnabled) 1f else .35f
                        setOnClickListener {
                            pendingProfileKeyId=null
                            try { onProfileKey(item.id,0);profileError=null }
                            catch(e:Exception) { profileError=e.message ?: "No se pudo guardar" }
                            render()
                        }
                    },LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginStart=dp(5) })
                    detail.addView(row,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(46)).apply { bottomMargin=dp(4) })
                }
                profileError?.let { message -> detail.addView(text(message,11f).apply { setTextColor(Color.rgb(255,179,190)) }) }
                if(pendingProfileKeyId!=null) detail.addView(text("Pulsa una tecla · Esc cancela",10f))
                detail.addView(text("＋ Nuevo perfil",12f).apply {
                    gravity=Gravity.CENTER;isClickable=true;typeface=android.graphics.Typeface.DEFAULT_BOLD
                    background=gradient(Color.rgb(52,127,222),Color.rgb(27,76,157),8)
                    contentDescription="Crear perfil"
                    setOnClickListener {
                        pendingProfileKeyId=null
                        try {
                            var number=profileItems.size+1
                            while(profileItems.any { it.name=="Perfil $number" }) number++
                            onCreateProfile("Perfil $number");profileError=null
                        } catch(e:Exception) { profileError=e.message ?: "No se pudo crear" }
                        render()
                    }
                },LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(38)).apply { topMargin=dp(5) })
                centerMenu()
            }
            pendingProfileKeyId=null;profileError=null;renderProfiles={ render() };render()
        }
        val mode=if(playing) "Control" else "Teclado"
        val entries=listOf("Mapear" to R.drawable.mm_map,
            mode to if(playing) R.drawable.mm_controller else R.drawable.mm_keyboard,
            "Perfiles" to R.drawable.mm_profiles,
            "Ajustes" to R.drawable.mm_settings,"Cerrar sesi\u00f3n" to R.drawable.mm_close)
        for((name,icon) in entries) {
            val enabled=when(name) { "Mapear"->editEnabled;mode->playing || playEnabled;else->true }
            row.addView(tile(if(name==mode) "$name\nActivado" else name,icon) {
                when(name) {
                    "Mapear" -> { closeMenu(); onEdit() }
                    mode -> if(playing) onKeyboard() else { closeMenu(); onPlay() }
                    "Perfiles" -> profilePanel()
                    "Ajustes" -> appearancePanel()
                    else -> { closeMenu(); onStop() }
                }
            }.apply { isEnabled=enabled; alpha=if(enabled) 1f else .4f; contentDescription=if(name==mode) "Cambiar modo: $mode activado" else name },
                LinearLayout.LayoutParams(0,dp(76),1f).apply { setMargins(dp(3),dp(4),dp(3),0) })
        }
        panel.addView(row); panel.addView(detail)
        val scroll = ScrollView(service).apply { addView(panel); isVerticalScrollBarEnabled=false }
        val p = params("MobiMapper · Opciones")
        centerMenuGeometry(panel,p)
        installMenuDrag(menuGrip,scroll,p);installMenuDrag(status,scroll,p)
        windows.addView(scroll, p); menu = scroll;menuParams=p;menuPanel=panel
        onVisualSettingsChanged()
    }

    private fun centerMenuGeometry(panel:View,p:WindowManager.LayoutParams) {
        val size=screen();p.width=minOf(dp(420),size.x-dp(16))
        panel.measure(View.MeasureSpec.makeMeasureSpec(p.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        p.height=minOf(size.y,panel.measuredHeight)
        p.x=maxOf(0,(size.x-p.width)/2);p.y=0
    }
    private fun centerMenu() {
        val view=menu ?: return;val p=menuParams ?: return;val panel=menuPanel ?: return
        centerMenuGeometry(panel,p);windows.updateViewLayout(view,p)
        onVisualSettingsChanged()
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun installMenuDrag(handle:View,view:View,p:WindowManager.LayoutParams) {
        var rawX=0f;var rawY=0f;var startX=0;var startY=0
        handle.setOnTouchListener { _,event ->
            if(menu!==view) return@setOnTouchListener false
            fun move() {
                val size=screen()
                p.x=(startX+(event.rawX-rawX).roundToInt()).coerceIn(0,maxOf(0,size.x-p.width))
                p.y=(startY+(event.rawY-rawY).roundToInt()).coerceIn(0,maxOf(0,size.y-p.height))
                windows.updateViewLayout(view,p)
                onVisualSettingsChanged()
            }
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { rawX=event.rawX;rawY=event.rawY;startX=p.x;startY=p.y;true }
                MotionEvent.ACTION_MOVE -> { move();true }
                MotionEvent.ACTION_UP -> { move();true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun slider(panel: LinearLayout, name: String, min: Int, max: Int, initial: Int,
                       format: (Int) -> String, change: (Int) -> Unit) {
        val caption = text("$name: ${format(initial)}", 11f)
        panel.addView(caption)
        panel.addView(SeekBar(service).apply {
            this.min = min; this.max = max; progress = initial
            contentDescription = name
            progressTintList=ColorStateList.valueOf(Color.rgb(108,182,255))
            progressBackgroundTintList=ColorStateList.valueOf(Color.rgb(43,73,121))
            thumbTintList=ColorStateList.valueOf(Color.rgb(186,224,255))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { saveStyle() }
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { change(progress); caption.text = "$name: ${format(progress)}" }
                }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
    }
    private fun saveStyle() {
        if (!settings.save(style)) {
            style = settings.value; applyBubble()
            Toast.makeText(service, "No se pudieron guardar los ajustes de burbuja", Toast.LENGTH_SHORT).show()
        }
        onVisualSettingsChanged()
    }
    private fun dismissMenu() { closeMenu();onCursorReturn() }
    fun containsCursorPoint(x:Float,y:Float):Boolean {
        val p=bubbleParams ?: return false
        return bubble!=null && interactionEnabled && !menuVisible && x>=p.x && x<p.x+p.width && y>=p.y && y<p.y+p.height
    }
    /** Local widget dispatch only; these events never go through the game injector. */
    fun dispatchCursorTouch(action:Int,x:Float,y:Float):Boolean {
        val view=bubble ?: return false;val p=bubbleParams ?: return false
        val now=android.os.SystemClock.uptimeMillis()
        if(action==MotionEvent.ACTION_DOWN) cursorDownTime=now
        val event=MotionEvent.obtain(cursorDownTime,now,action,x,y,0).apply {
            source=android.view.InputDevice.SOURCE_MOUSE;offsetLocation(-p.x.toFloat(),-p.y.toFloat())
        }
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    /** Only an explicit profile-key picker owns this key; the service pairs its physical UP. */
    fun captureProfileKey(code:Int):Boolean {
        val id=pendingProfileKeyId ?: return false
        if(!menuVisible || !interactionEnabled) return false
        if(code==KeyEvent.KEYCODE_ESCAPE) {
            pendingProfileKeyId=null;profileError=null;renderProfiles?.invoke();return true
        }
        try {
            onProfileKey(id,code);pendingProfileKeyId=null;profileError=null
        } catch(e:Exception) { profileError=e.message ?: "Tecla no disponible" }
        renderProfiles?.invoke();return true
    }
    private fun profileKeyLabel(code:Int):String=when(code) {
        0 -> "Asignar"
        KeyEvent.KEYCODE_SPACE -> "Espacio"
        KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT -> "Mayús"
        KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl"
        KeyEvent.KEYCODE_ALT_LEFT,KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }
    fun closeMenu() {
        pendingProfileKeyId=null;profileError=null;renderProfiles=null
        val view = menu ?: return; menu = null;menuParams=null;menuPanel=null; windows.removeViewImmediate(view);onVisualSettingsChanged()
    }
    fun hide() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closeMenu(); val view = bubble ?: return; bubble = null; bubbleParams = null; windows.removeViewImmediate(view)
    }
    fun close() = hide()
    private fun gradient(top:Int,bottom:Int,radius:Int,stroke:Int?=null) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,intArrayOf(top,bottom),
    ).apply { cornerRadius=dp(radius).toFloat(); if(stroke!=null) setStroke(dp(1),stroke) }
    private fun text(value: String, size: Float) = TextView(service).apply { text = value; textSize = size; setTextColor(Color.rgb(224,239,255)); setPadding(0, dp(2), 0, dp(2)) }
    private fun button(value: String, enabled: Boolean, action: () -> Unit) = ImageButton(service).apply {
        setImageResource(R.drawable.mm_close); scaleType=ImageView.ScaleType.FIT_CENTER
        imageTintList=null
        contentDescription=value; isEnabled = enabled
        background=gradient(Color.rgb(42,82,145),Color.rgb(20,48,97),10,Color.rgb(76,126,195))
        setPadding(dp(4),dp(4),dp(4),dp(4)); minimumHeight = dp(36)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
    }
    private fun tile(name:String,icon:Int,action:()->Unit):View = LinearLayout(service).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; isClickable=true; contentDescription=name
        val active=name.contains('\n')
        background=gradient(if(active) Color.rgb(68,139,237) else Color.rgb(42,91,166),
            if(active) Color.rgb(31,86,183) else Color.rgb(20,50,105),12,
            if(active) Color.rgb(148,204,255) else Color.rgb(67,111,176))
        addView(ImageView(service).apply { setImageResource(icon); imageTintList=null; scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(32),dp(32)))
        addView(text(name.substringBefore('\n'),10.5f).apply {
            gravity=Gravity.CENTER; typeface=android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        if(active) addView(text(name.substringAfter('\n'),8.5f).apply {
            gravity=Gravity.CENTER; typeface=android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(17,49,99)); setPadding(dp(5),dp(1),dp(5),dp(1))
            background=gradient(Color.rgb(222,241,255),Color.rgb(164,209,255),5)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        setOnClickListener { action() }
    }
    private fun screen(): Point = Point().also {
        @Suppress("DEPRECATION")
        service.getSystemService(DisplayManager::class.java).getDisplay(0).getRealSize(it)
    }
    private fun baseFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    private fun params(title: String) = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START; this.title = title
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density + .5f).toInt()
}
