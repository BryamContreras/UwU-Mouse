package dev.uwumouse.app.editor

/** Separates silent view refreshes, live dragging and one committed button step. */
internal class SliderValueController(
    val minimum:Int,
    val maximum:Int,
    initial:Int,
    private val changed:(Int)->Unit,
    private val committed:()->Unit,
) {
    init { require(minimum<=maximum) }
    var value=initial.coerceIn(minimum,maximum)
        private set
    val canDecrease:Boolean get()=value>minimum
    val canIncrease:Boolean get()=value<maximum

    fun synchronize(value:Int) { this.value=value.coerceIn(minimum,maximum) }

    fun dragTo(value:Int):Boolean {
        val next=value.coerceIn(minimum,maximum)
        if(next==this.value) return false
        this.value=next
        changed(next)
        return true
    }

    fun finishDrag() = committed()

    fun step(direction:Int):Boolean {
        require(direction==-1 || direction==1)
        val next=(value.toLong()+direction).coerceIn(minimum.toLong(),maximum.toLong()).toInt()
        if(!dragTo(next)) return false
        committed()
        return true
    }
}
