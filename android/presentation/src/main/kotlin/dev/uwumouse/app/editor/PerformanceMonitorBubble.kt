package dev.uwumouse.app.editor

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.uwumouse.presentation.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A passive monitor: no timer, polling, or input handling while used over a game. */
class PerformanceMonitorBubble(private val service:Context,private val overlayContext:Context=service,visual:StatusVisualPort?=null,
                               private val onPositionChanged:(Float,Float)->Unit={_,_->}) {
    private class MonitorViews(val root:LinearLayout,val battery:TextView,val fps:TextView,val icon:ImageView)

    private val windows=overlayContext.getSystemService(WindowManager::class.java)
    private val uiContext = DesktopPresentation.contextForControls(overlayContext)
    private val screenSize=Point()
    private val statusVisual=StatusVisualRenderer(visual,StatusVisualPort.MONITOR,
        runCatching { overlayContext.display?.displayId }.getOrNull() ?: 0)
    private var views:MonitorViews?=null
    private val view:View? get()=views?.root
    private var params:WindowManager.LayoutParams?=null
    private var batteryReceiver:BroadcastReceiver?=null
    private var batteryPercent:Int?=null
    private var displayedFps:Int?=null
    private var positionX:Int?=null
    private var positionY:Int?=null
    private var movable=false
    private var opacity=1f
    private var sizePercent=BubbleSettings.DEFAULT_MONITOR_SIZE_PERCENT
    private var normalizedX:Float?=null
    private var normalizedY:Float?=null
    private var dragging=false
    private var dragMoved=false
    private var startX=0f;private var startY=0f
    private var originalX=0;private var originalY=0

    val visible:Boolean get()=view!=null
    val fps:Int? get()=displayedFps
    val battery:Int? get()=batteryPercent

    /** Must run on the main thread. Input is enabled only for an explicitly requested reposition mode. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(movable:Boolean=false) {
        check(Looper.myLooper()==Looper.getMainLooper())
        val existing=view
        if(existing!=null) { setMovable(movable);return }
        this.movable=movable
        val battery=TextView(uiContext).apply {
            setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD
            gravity=Gravity.CENTER_VERTICAL;text="--%";includeFontPadding=false
        }
        val fps=TextView(uiContext).apply {
            setTextColor(Color.rgb(224,239,255));typeface=Typeface.DEFAULT_BOLD
            gravity=Gravity.CENTER_VERTICAL;text="-- FPS";includeFontPadding=false
        }
        val root=LinearLayout(uiContext).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
            alpha=opacity
            background=GradientDrawable().apply {
                setColor(Color.argb(105,8,17,34))
            }
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val batteryIcon=ImageView(uiContext).apply {
            setImageResource(R.drawable.mm_battery);scaleType=ImageView.ScaleType.FIT_CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(batteryIcon,LinearLayout.LayoutParams(0,0))
        root.addView(battery,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(fps,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT))
        root.setOnTouchListener { _,event ->
            if(!this.movable) return@setOnTouchListener false
            handleDrag(event.actionMasked,event.rawX,event.rawY)
        }
        val content=MonitorViews(root,battery,fps,batteryIcon)
        val screen=screen()
        val extent=measureContent(content,screen)
        val p=WindowManager.LayoutParams(extent.x,extent.y,OverlayWindows.type(overlayContext),
            flags(movable),PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START;title="UwU Mouse · Monitor"
            positionWindow(this,screen)
            alpha=if(movable) 1f else OverlayWindows.nonTouchAlpha(overlayContext)
            layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        windows.addView(root,p)
        views=content;params=p
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context?,intent:Intent?) {
                if(intent?.action!=Intent.ACTION_BATTERY_CHANGED) return
                val level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)
                val scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
                val percent=if(level>=0 && scale>0) ((level.toLong()*100)/scale).toInt().coerceIn(0,100) else null
                if(batteryPercent!=percent) { batteryPercent=percent;render() }
            }
        }
        try {
            batteryReceiver=receiver
            val sticky=service.registerReceiver(receiver,IntentFilter(Intent.ACTION_BATTERY_CHANGED),Context.RECEIVER_NOT_EXPORTED)
            if(sticky!=null) receiver.onReceive(service,sticky)
        } catch(failure:RuntimeException) {
            batteryReceiver=null;hide();throw failure
        }
        render()
        refreshWindowOpacity()
    }

    /** A null/invalid sample is deliberately rendered as unavailable, never as display refresh rate. */
    fun updateFps(value:Float?) {
        check(Looper.myLooper()==Looper.getMainLooper())
        val rounded=value?.takeIf { it.isFinite() && it>=0f && it<=1000f }?.roundToInt()
        if(displayedFps!=rounded) { displayedFps=rounded;render() }
    }

    /** Opacity is a view property; the window still obeys the Android touch-opacity budget. */
    fun updateSettings(opacity:Float,sizePercent:Int,x:Float?,y:Float?) {
        check(Looper.myLooper()==Looper.getMainLooper())
        require(opacity.isFinite() && opacity in .1f..1f)
        require(sizePercent in BubbleSettings.MIN_MONITOR_SIZE_PERCENT..BubbleSettings.MAX_MONITOR_SIZE_PERCENT)
        require((x==null && y==null) || (x!=null && y!=null && x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f))
        val resized=this.sizePercent!=sizePercent
        val moved=normalizedX!=x || normalizedY!=y
        if(!resized && !moved && this.opacity==opacity) return
        if(resized && dragging) handleDrag(MotionEvent.ACTION_CANCEL,startX,startY)
        this.opacity=opacity;this.sizePercent=sizePercent;normalizedX=x;normalizedY=y
        val p=params ?: return
        val content=views ?: return
        if((resized || moved) && !dragging) {
            val screen=screen()
            if(resized) {
                val extent=measureContent(content,screen)
                p.width=extent.x;p.height=extent.y
                statusVisual.invalidate()
            }
            positionWindow(p,screen)
            updateVisual(content.root,p)
            windows.updateViewLayout(content.root,p)
        } else {
            refreshWindowOpacity()
        }
    }

    fun containsCursorPoint(x:Float,y:Float):Boolean {
        val p=params ?: return false
        return view!=null && x.isFinite() && y.isFinite() && x>=p.x && x<p.x+p.width && y>=p.y && y<p.y+p.height
    }

    /** Called only by the owner after it has safely captured a mouse gesture for this monitor. */
    fun dispatchCursorTouch(action:Int,x:Float,y:Float):Boolean = handleDrag(action,x,y)

    private fun handleDrag(action:Int,x:Float,y:Float):Boolean {
        val p=params ?: return false;val root=view ?: return false
        if(!x.isFinite() || !y.isFinite()) return false
        return when(action) {
            MotionEvent.ACTION_DOWN -> {
                if(!containsCursorPoint(x,y)) return false
                dragging=true;dragMoved=false;startX=x;startY=y;originalX=p.x;originalY=p.y;true
            }
            MotionEvent.ACTION_MOVE -> {
                if(!dragging) return false
                val dx=x-startX;val dy=y-startY
                if(dragMoved || abs(dx)+abs(dy)>dp(3)) {
                    dragMoved=true
                    val size=screen();val nextX=(originalX+dx.roundToInt()).coerceIn(0,(size.x-p.width).coerceAtLeast(0))
                    val nextY=(originalY+dy.roundToInt()).coerceIn(0,(size.y-p.height).coerceAtLeast(0))
                    if(p.x!=nextX || p.y!=nextY) {
                        p.x=nextX;p.y=nextY
                        positionX=p.x;positionY=p.y;updateVisual(root,p);windows.updateViewLayout(root,p)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if(!dragging) return false
                dragging=false
                if(dragMoved) {
                    val size=screen();val availableX=(size.x-p.width).coerceAtLeast(0);val availableY=(size.y-p.height).coerceAtLeast(0)
                    val nx=if(availableX>0) p.x.toFloat()/availableX else 0f
                    val ny=if(availableY>0) p.y.toFloat()/availableY else 0f
                    normalizedX=nx;normalizedY=ny;onPositionChanged(nx,ny)
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if(!dragging) return false
                dragging=false
                if(p.x!=originalX || p.y!=originalY) {
                    p.x=originalX;p.y=originalY;positionX=p.x;positionY=p.y
                    updateVisual(root,p);windows.updateViewLayout(root,p)
                }
                true
            }
            else -> false
        }
    }

    fun setMovable(enabled:Boolean) {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(movable && !enabled && dragging) handleDrag(MotionEvent.ACTION_CANCEL,startX,startY)
        movable=enabled
        val root=view ?: return
        val p=params ?: return
        val oldFlags=p.flags;val oldAlpha=p.alpha
        p.flags=flags(enabled);updateVisual(root,p)
        if(p.flags!=oldFlags || p.alpha!=oldAlpha) windows.updateViewLayout(root,p)
    }

    private fun updateVisual(root:View,p:WindowManager.LayoutParams) {
        val trusted=if(!movable) statusVisual.show(root,p,opacity) else { statusVisual.hide();false }
        p.alpha=if(trusted) 0f else if(movable) 1f else OverlayWindows.nonTouchAlpha(overlayContext)
        root.alpha=if(trusted) 0f else opacity
    }

    fun refreshWindowOpacity() { setMovable(movable) }
    fun invalidateTrustedVisual() { statusVisual.hide();refreshWindowOpacity() }

    fun hide() {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(dragging) handleDrag(MotionEvent.ACTION_CANCEL,startX,startY)
        statusVisual.hide()
        batteryReceiver?.let { service.unregisterReceiver(it) };batteryReceiver=null
        val old=view ?: return
        views=null;params=null;batteryPercent=null;displayedFps=null
        try { windows.removeViewImmediate(old) } catch (_:IllegalArgumentException) { /* Already detached. */ }
    }

    private fun render() {
        val content=views ?: return
        val battery=batteryPercent?.let { "$it%" } ?: "--%"
        val fps=displayedFps?.let { "$it FPS" } ?: "-- FPS"
        if(content.battery.text.toString()!=battery) content.battery.text=battery
        if(content.fps.text.toString()!=fps) content.fps.text=fps
        content.root.contentDescription="Monitor: batería $battery, $fps"
        statusVisual.invalidate()
        refreshWindowOpacity()
    }

    private fun measureContent(content:MonitorViews,screen:Point):Point {
        val metrics=uiContext.resources.displayMetrics
        val textPixels=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,11f,metrics)
        fun measure(scale:Float):Point {
            fun scaledDp(value:Int)=(value*metrics.density*scale).roundToInt()
            val textSize=textPixels*scale
            for(label in listOf(content.battery,content.fps)) {
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX,textSize)
                label.setShadowLayer(scaledDp(2).toFloat(),0f,scaledDp(1).toFloat(),Color.argb(220,0,0,0))
            }
            val horizontal=scaledDp(4);val vertical=scaledDp(2)
            content.root.setPadding(horizontal,vertical,horizontal,vertical)
            (content.root.background as GradientDrawable).cornerRadius=scaledDp(8).toFloat()
            val iconSize=scaledDp(16)
            val iconParams=content.icon.layoutParams as LinearLayout.LayoutParams
            iconParams.width=iconSize;iconParams.height=iconSize;iconParams.marginEnd=vertical
            content.icon.layoutParams=iconParams
            val fpsParams=content.fps.layoutParams as LinearLayout.LayoutParams
            fpsParams.marginStart=horizontal;content.fps.layoutParams=fpsParams
            // Reserve the widest text that can be displayed, using the actual accessibility font.
            val battery=content.battery.paint;val fps=content.fps.paint
            val textWidth=ceil(battery.measureText("100%")).toInt()+ceil(fps.measureText("1000 FPS")).toInt()
            val textHeight=ceil(maxOf(battery.fontMetrics.descent-battery.fontMetrics.ascent,
                fps.fontMetrics.descent-fps.fontMetrics.ascent)).toInt()
            return Point(horizontal*3+iconSize+vertical*2+textWidth,maxOf(scaledDp(26),textHeight+horizontal))
        }
        val scale=sizePercent/100f
        val extent=measure(scale)
        val fit=minOf(1f,screen.x.toFloat()/extent.x,screen.y.toFloat()/extent.y)
        if(fit>=1f) return extent
        return measure(scale*fit).apply {
            x=x.coerceIn(1,screen.x);y=y.coerceIn(1,screen.y)
        }
    }

    private fun positionWindow(p:WindowManager.LayoutParams,screen:Point) {
        val availableX=(screen.x-p.width).coerceAtLeast(0);val availableY=(screen.y-p.height).coerceAtLeast(0)
        p.x=(normalizedX?.let { (it*availableX).roundToInt() } ?: positionX ?: (availableX-dp(12))).coerceIn(0,availableX)
        p.y=(normalizedY?.let { (it*availableY).roundToInt() } ?: positionY ?: dp(12)).coerceIn(0,availableY)
    }
    private fun flags(movable:Boolean)=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        if(movable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    private fun dp(value:Int)=(value*uiContext.resources.displayMetrics.density+.5f).toInt()
    private fun screen():Point=screenSize.also {
        @Suppress("DEPRECATION")
        requireNotNull(if(overlayContext===service) service.getSystemService(DisplayManager::class.java).getDisplay(0)
            else overlayContext.display) { "Pantalla del monitor no disponible" }.getRealSize(it)
    }
}
