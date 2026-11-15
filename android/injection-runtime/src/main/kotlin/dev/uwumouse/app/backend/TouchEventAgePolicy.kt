package dev.uwumouse.app.backend

import dev.uwumouse.app.engine.TouchFrame

/** Synthetic touch-event age, checked after potentially slow admission work and before prepare. */
object TouchEventAgePolicy {
    const val MAX_AGE_MILLIS=5_000L

    fun admits(frame:TouchFrame,nowMillis:Long):Boolean =
        frame.eventTime>=0L && nowMillis>=frame.eventTime && nowMillis-frame.eventTime<MAX_AGE_MILLIS
}
