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
    private val windows=overlayContext.getSystemService(WindowManager::class.java)
    private val screenSize=Point()
    private val statusVisual=StatusVisualRenderer(visual,StatusVisualPort.MONITOR,
        runCatching { overlayContext.display?.displayId }.getOrNull() ?: 0)
    private var view:View?=null
    private var params:WindowManager.LayoutParams?=null
    private var batteryLabel:TextView?=null
    private var fpsLabel:TextView?=null
    private var batteryReceiver:BroadcastReceiver?=null
    private var batteryPercent:Int?=null
    private var displayedFps:Int?=null
    private var positionX:Int?=null
    private var positionY:Int?=null
    private var movable=false
    private var opacity=1f
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
        val battery=TextView(overlayContext).apply {
            textSize=11f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD
            gravity=Gravity.CENTER_VERTICAL;text="--%";includeFontPadding=false
            setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),Color.argb(220,0,0,0))
        }
        val fps=TextView(overlayContext).apply {
            textSize=11f;setTextColor(Color.rgb(224,239,255));typeface=Typeface.DEFAULT_BOLD
            gravity=Gravity.CENTER_VERTICAL;text="-- FPS";includeFontPadding=false
            setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),Color.argb(220,0,0,0))
        }
        val root=LinearLayout(overlayContext).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
            setPadding(dp(4),dp(2),dp(4),dp(2))
            alpha=opacity
            background=GradientDrawable().apply {
                setColor(Color.argb(105,8,17,34))
                cornerRadius=dp(8).toFloat()
            }
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val batteryIcon=ImageView(overlayContext).apply {
            setImageResource(R.drawable.mm_battery);scaleType=ImageView.ScaleType.FIT_CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(batteryIcon,LinearLayout.LayoutParams(dp(16),dp(16)).apply { marginEnd=dp(2) })
        root.addView(battery,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(fps,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart=dp(4) })
        root.setOnTouchListener { _,event ->
            if(!this.movable) return@setOnTouchListener false
            handleDrag(event.actionMasked,event.rawX,event.rawY)
        }
        // Reserve only the space needed for the longest displayed values, using the
        // actual scaled fonts so large system text does not truncate either label.
        val textWidth=ceil(battery.paint.measureText("100%")).toInt()+ceil(fps.paint.measureText("1000 FPS")).toInt()
        val textHeight=ceil(maxOf(battery.paint.fontMetrics.descent-battery.paint.fontMetrics.ascent,
            fps.paint.fontMetrics.descent-fps.paint.fontMetrics.ascent).toDouble()).toInt()
        val width=dp(4)*2+dp(16)+dp(2)+dp(4)+textWidth+dp(2)
        val height=maxOf(dp(26),textHeight+dp(4));val size=screen()
        val p=WindowManager.LayoutParams(width,height,OverlayWindows.type(overlayContext),
            flags(movable),PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START;title="UwU Mouse · Monitor"
            val availableX=(size.x-width).coerceAtLeast(0);val availableY=(size.y-height).coerceAtLeast(0)
            x=(normalizedX?.let { (it*availableX).roundToInt() } ?: positionX ?: (availableX-dp(12))).coerceIn(0,availableX)
            y=(normalizedY?.let { (it*availableY).roundToInt() } ?: positionY ?: dp(12)).coerceIn(0,availableY)
            alpha=if(movable) 1f else OverlayWindows.nonTouchAlpha(overlayContext)
            layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        windows.addView(root,p)
        view=root;params=p;batteryLabel=battery;fpsLabel=fps
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
    fun updateSettings(opacity:Float,x:Float?,y:Float?) {
        check(Looper.myLooper()==Looper.getMainLooper())
        require(opacity.isFinite() && opacity in .1f..1f)
        require((x==null && y==null) || (x!=null && y!=null && x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f))
        this.opacity=opacity
        val changed=normalizedX!=x || normalizedY!=y
        normalizedX=x;normalizedY=y
        if(changed && !dragging && x!=null && y!=null) {
            val p=params ?: return;val root=view ?: return;val size=screen()
            p.x=(x*(size.x-p.width).coerceAtLeast(0)).roundToInt()
            p.y=(y*(size.y-p.height).coerceAtLeast(0)).roundToInt()
            positionX=p.x;positionY=p.y;windows.updateViewLayout(root,p)
        }
        refreshWindowOpacity()
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
        view=null;params=null;batteryLabel=null;fpsLabel=null;batteryPercent=null;displayedFps=null
        try { windows.removeViewImmediate(old) } catch (_:IllegalArgumentException) { /* Already detached. */ }
    }

    private fun render() {
        val battery=batteryPercent?.let { "$it%" } ?: "--%"
        val fps=displayedFps?.let { "$it FPS" } ?: "-- FPS"
        batteryLabel?.let { if(it.text.toString()!=battery) it.text=battery }
        fpsLabel?.let { if(it.text.toString()!=fps) it.text=fps }
        view?.contentDescription="Monitor: batería $battery, $fps"
        statusVisual.invalidate()
        if(view!=null) refreshWindowOpacity()
    }
    private fun flags(movable:Boolean)=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        if(movable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    private fun dp(value:Int)=(value*overlayContext.resources.displayMetrics.density+.5f).toInt()
    private fun screen():Point=screenSize.also {
        @Suppress("DEPRECATION")
        requireNotNull(if(overlayContext===service) service.getSystemService(DisplayManager::class.java).getDisplay(0)
            else overlayContext.display) { "Pantalla del monitor no disponible" }.getRealSize(it)
    }
}
