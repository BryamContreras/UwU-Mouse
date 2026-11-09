package dev.uwumouse.app

import android.app.Application
import android.content.Context
import dev.uwumouse.app.runtime.MapperRuntimeComposition

/** Composition precedes providers as well as Activity and Service entry points. */
class MapperApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MapperRuntimeComposition.initialize()
    }
}
