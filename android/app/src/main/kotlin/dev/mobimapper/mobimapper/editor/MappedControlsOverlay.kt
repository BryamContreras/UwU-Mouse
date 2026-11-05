package dev.mobimapper.mobimapper.editor

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import dev.mobimapper.mobimapper.profile.GameProfile
import kotlin.math.min

/** Static presentation only: no touch/focus, no entry into the input stream or per-mouse work. */
class MappedControlsOverlay(private val service:AccessibilityService) {
    private val windows=service.getSystemService(WindowManager::class.java)
    private var view:ControlsView?=null
    private var profile:GameProfile?=null
    val visible:Boolean get()=view!=null

    fun show(profile:GameProfile,opacity:Float) {
        val current=view
        if(current!=null) {
            if(this.profile!==profile) { this.profile=profile;current.prepare(profile) }
            if(current.alpha!=opacity) current.alpha=opacity
            return
        }
        val controls=ControlsView(service).apply {
            contentDescription="MobiMapper · controles visibles"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            alpha=opacity;prepare(profile)
        }
        val p=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START;title="MobiMapper · Controles"
            layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;setFitInsetsTypes(0)
        }
        windows.addView(controls,p);view=controls;this.profile=profile
    }
    fun hide() { val old=view ?: return;view=null;profile=null;windows.removeViewImmediate(old) }
    fun avoid(bounds:Rect?) { view?.avoid(bounds) }

    private class ControlsView(service:AccessibilityService):View(service) {
        private data class Marker(val x:Float,val y:Float,val radius:Float,val label:String,val detail:String="")
        private var markers:List<Marker> = emptyList()
        private val density=resources.displayMetrics.density
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(90,29,99,184) }
        private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(125,194,255);style=Paint.Style.STROKE;strokeWidth=1.7f*density }
        private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.DEFAULT_BOLD }
        private val shadow=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(17,44,78);textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.DEFAULT_BOLD;style=Paint.Style.STROKE;strokeWidth=1.4f*density }
        private val origin=IntArray(2)
        private val excluded=Rect()
        fun avoid(bounds:Rect?) {
            if(bounds==null) { if(!excluded.isEmpty) { excluded.setEmpty();invalidate() } }
            else if(excluded!=bounds) { excluded.set(bounds);invalidate() }
        }
        fun prepare(p:GameProfile) {
            val area=p.calibration.area
            fun key(code:Int)=KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").let {
                when(it) { "SPACE"->"ESP";"SHIFT_LEFT","SHIFT_RIGHT"->"MAY";"CTRL_LEFT","CTRL_RIGHT"->"CTRL";else->it }
            }
            fun trigger(kind:String,code:Int)=if(kind=="key") key(code) else "M${when(code){1->1;2->2;4->3;8->4;else->5}}"
            val result=ArrayList<Marker>(p.actions.size+p.macros.size+2)
            if(p.joystick.enabled) result+=Marker(area.displayX(p.joystick.x),area.displayY(p.joystick.y),
                p.joystick.radius*min(area.width,area.height),"WASD")
            if(p.camera.enabled) result+=Marker(area.displayX(p.camera.x),area.displayY(p.camera.y),
                p.camera.markerSizeDp*density/2,key(p.shortcuts.cameraToggle))
            for(action in p.actions) result+=Marker(area.displayX(action.x),area.displayY(action.y),
                action.markerSizeDp*density/2,trigger(action.triggerKind,action.code),
                when(action.behavior){"repeat"->"R";"cursorToggle"->"MAP";else->""})
            for(macro in p.macros) result+=Marker(area.displayX(macro.x),area.displayY(macro.y),
                macro.markerSizeDp*density/2,trigger(macro.triggerKind,macro.code),"MAC")
            markers=result;invalidate()
        }
        override fun onDraw(canvas:Canvas) {
            getLocationOnScreen(origin)
            canvas.save();canvas.translate(-origin[0].toFloat(),-origin[1].toFloat())
            if(!excluded.isEmpty) canvas.clipOutRect(excluded)
            for(marker in markers) {
                canvas.drawCircle(marker.x,marker.y,marker.radius,fill);canvas.drawCircle(marker.x,marker.y,marker.radius,ring)
                text.textSize=min(14*density,marker.radius*1.4f/maxOf(2,marker.label.length)*1.6f)
                shadow.textSize=text.textSize
                val y=marker.y-(text.ascent()+text.descent())/2
                canvas.drawText(marker.label,marker.x,y,shadow);canvas.drawText(marker.label,marker.x,y,text)
                if(marker.detail.isNotEmpty()) {
                    text.textSize=8*density;shadow.textSize=text.textSize
                    canvas.drawText(marker.detail,marker.x,marker.y+marker.radius*.72f,shadow)
                    canvas.drawText(marker.detail,marker.x,marker.y+marker.radius*.72f,text)
                }
            }
            canvas.restore()
        }
    }
}
