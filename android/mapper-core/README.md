# Mapper core

Pure Kotlin/JVM module containing mapping, touch composition, profile validation
and serialization, session policies, queue storage, recovery policies and editor
models. It has no Android, Flutter, Binder, window or device access.

Platform adapters provide clocks, scheduling, storage, input events and the touch
sink. The core never reads physical devices or injects Android input itself.
`ProfileStorage` remains the persistence port; the Android `AtomicFile` adapter
stays outside this module.

Version 0.3.4 adopts UwU Mouse package names while retaining profile schema 9 and
the serialized profile format. The Android application identity migration is
documented separately; preserving a file format does not migrate Android data.

`RepeatRunner` supports both held input and press-to-toggle loops. Optional
`repeatToggle` and `repeatDurationSeconds` fields default to false/zero in older
profiles; zero disables the timer, and the accepted duration is 0–86400 seconds.
Each active repeat has one scheduled pulse/deadline boundary. Cancellation
invalidates its generation and removes pending work; missed intervals never
produce catch-up bursts. Platform adapters own global pause and touch release.

JSON is a compile-only dependency because Android supplies `org.json`. JVM tests
use the matching JSON implementation explicitly. Bytecode targets Java 17.

Run the module's tests with `:mapper-core:test`. Android services, resources and
`SessionKeys` (the Android key-code adapter) remain outside the module.
