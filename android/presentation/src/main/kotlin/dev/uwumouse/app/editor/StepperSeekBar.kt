package dev.uwumouse.app.editor

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar

/** The same seek bar value and drag behavior, with one-unit buttons on either side. */
internal class StepperSeekBar(
    context:Context,
    name:String,
    minimum:Int,
    maximum:Int,
    initial:Int,
    onValueChanged:(Int)->Unit,
    onValueCommitted:()->Unit={},
):LinearLayout(context) {
    private val values=SliderValueController(minimum,maximum,initial,onValueChanged,onValueCommitted)
    val seekBar=SeekBar(context).apply {
        min=minimum;max=maximum;progress=values.value
        contentDescription=name
        EditorSeekBarStyle.apply(this)
    }
    private val decrease=stepButton("−","Disminuir ${name.replaceFirstChar { it.lowercase() }}")
    private val increase=stepButton("+","Aumentar ${name.replaceFirstChar { it.lowercase() }}")
    private var initialized=false

    init {
        orientation=HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
        addView(decrease,LayoutParams(dp(40),dp(44)))
        addView(seekBar,LayoutParams(0,dp(44),1f))
        addView(increase,LayoutParams(dp(40),dp(44)))
        decrease.setOnClickListener { if(isAttachedToWindow && isEnabled) { values.step(-1);refreshControls() } }
        increase.setOnClickListener { if(isAttachedToWindow && isEnabled) { values.step(1);refreshControls() } }
        seekBar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar:SeekBar?)=Unit
            override fun onStopTrackingTouch(seekBar:SeekBar?) {
                if(isAttachedToWindow && isEnabled) values.finishDrag()
            }
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {
                if(fromUser && isAttachedToWindow && isEnabled) values.dragTo(progress)
                refreshControls()
            }
        })
        initialized=true
        refreshControls()
    }

    /** Refreshing a draft/style never invokes live-change or persistence callbacks. */
    fun setValue(value:Int) { values.synchronize(value);refreshControls() }

    override fun setEnabled(enabled:Boolean) {
        super.setEnabled(enabled)
        if(initialized) refreshControls()
    }

    private fun refreshControls() {
        if(seekBar.progress!=values.value) seekBar.progress=values.value
        seekBar.isEnabled=isEnabled
        decrease.isEnabled=isEnabled && values.canDecrease
        increase.isEnabled=isEnabled && values.canIncrease
        decrease.alpha=if(decrease.isEnabled) 1f else .45f
        increase.alpha=if(increase.isEnabled) 1f else .45f
    }

    private fun stepButton(symbol:String,description:String)=Button(context).apply {
        text=symbol;textSize=21f;isAllCaps=false;gravity=Gravity.CENTER;includeFontPadding=false
        minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0)
        contentDescription=description;stateListAnimator=null
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
            intArrayOf(Color.rgb(139,157,183),Color.rgb(234,244,255))))
        fun surface(fill:Int,stroke:Int)=GradientDrawable().apply {
            cornerRadius=dp(9).toFloat();setColor(fill);setStroke(dp(1),stroke)
        }
        background=StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),surface(Color.rgb(25,35,51),Color.rgb(58,72,94)))
            addState(intArrayOf(android.R.attr.state_pressed),surface(Color.rgb(47,89,149),Color.rgb(139,189,255)))
            addState(intArrayOf(),surface(Color.rgb(29,53,86),Color.rgb(91,132,188)))
        }
    }

    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
}
