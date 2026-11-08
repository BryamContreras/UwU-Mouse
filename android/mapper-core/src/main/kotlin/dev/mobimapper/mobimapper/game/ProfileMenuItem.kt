package dev.mobimapper.mobimapper.game

/** Immutable control-plane rows; the editor never mutates a saved row in place. */
data class ProfileMenuItem(val id:String,val name:String,val switchKey:Int,val selected:Boolean)
