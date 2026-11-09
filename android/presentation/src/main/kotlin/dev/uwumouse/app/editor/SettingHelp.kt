package dev.uwumouse.app.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Contextual help stays beside its setting; touch and keyboard users can open it too. */
internal object SettingHelp {
    private var active:HelpButton?=null
    fun button(context: Context, name: String, explanation: String): View = HelpButton(context,name,explanation)

    /** ViewRoot's native tooltip dispatch is absent for locally routed cursor events. */
    private class HelpButton(context:Context,name:String,private val explanation:String):TextView(context) {
        private val density=resources.displayMetrics.density
        private var host:ViewGroup?=null
        private var popup:TextView?=null
        private var hovering=false
        private var pinned=false
        private val reveal=Runnable { if(hovering && isAttachedToWindow) showHelp() }
        private val expire=Runnable { dismiss() }
        private fun dp(value:Int)=(value*density+.5f).toInt()
        init {
            text="?";textSize=12f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER
            setTextColor(Color.rgb(188,221,255))
            background=GradientDrawable().apply {
                shape=GradientDrawable.OVAL;setColor(Color.rgb(24,49,84))
                setStroke(dp(1),Color.rgb(88,137,195))
            }
            contentDescription="Ayuda: $name. $explanation"
            isFocusable=true;isClickable=true;minimumWidth=dp(24);minimumHeight=dp(24)
            setOnHoverListener { _,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER,MotionEvent.ACTION_HOVER_MOVE -> if(!hovering) {
                        hovering=true;postDelayed(reveal,400)
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        hovering=false;removeCallbacks(reveal);if(!pinned) dismiss()
                    }
                }
                true
            }
            setOnClickListener { showPinned() }
            setOnLongClickListener { showPinned();true }
        }
        private fun showPinned() {
            removeCallbacks(reveal);pinned=true;showHelp();removeCallbacks(expire);postDelayed(expire,4_000)
        }
        private fun showHelp() {
            if(popup!=null || !isAttachedToWindow) return
            val root=rootView as? ViewGroup ?: return
            if(root.width<=dp(20) || root.height<=dp(20)) return
            active?.takeIf { it!==this }?.dismiss()
            val panel=TextView(context).apply {
                text=explanation;textSize=11f;setTextColor(Color.rgb(232,243,255))
                setPadding(dp(10),dp(8),dp(10),dp(8));maxLines=7
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background=GradientDrawable().apply {
                    cornerRadius=dp(8).toFloat();setColor(Color.rgb(15,34,61))
                    setStroke(dp(1),Color.rgb(96,153,218))
                }
            }
            panel.measure(MeasureSpec.makeMeasureSpec(minOf(dp(250),root.width-dp(12)),MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(root.height-dp(12),MeasureSpec.AT_MOST))
            val origin=IntArray(2);val anchor=IntArray(2)
            root.getLocationOnScreen(origin);getLocationOnScreen(anchor)
            val x=(anchor[0]-origin[0]+width-panel.measuredWidth).coerceIn(dp(6),maxOf(dp(6),root.width-panel.measuredWidth-dp(6)))
            val below=anchor[1]-origin[1]+height+dp(5)
            val y=(if(below+panel.measuredHeight<=root.height-dp(6)) below else anchor[1]-origin[1]-panel.measuredHeight-dp(5))
                .coerceIn(dp(6),maxOf(dp(6),root.height-panel.measuredHeight-dp(6)))
            panel.layout(x,y,x+panel.measuredWidth,y+panel.measuredHeight)
            root.overlay.add(panel);host=root;popup=panel;active=this
        }
        private fun dismiss() {
            removeCallbacks(reveal);removeCallbacks(expire);pinned=false
            popup?.let { host?.overlay?.remove(it) };popup=null;host=null
            if(active===this) active=null
        }
        override fun onDetachedFromWindow() { hovering=false;dismiss();super.onDetachedFromWindow() }
    }

    fun heading(context: Context, title: TextView, name: String, explanation: String): LinearLayout =
        LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            val density=resources.displayMetrics.density
            fun dp(value:Int)=(value*density+.5f).toInt()
            addView(title,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
            addView(button(context,name,explanation),LinearLayout.LayoutParams(dp(24),dp(24)).apply {
                leftMargin=dp(6);topMargin=dp(3);bottomMargin=dp(3)
            })
        }
}
