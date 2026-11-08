# Mapper core

Pure Kotlin/JVM module containing mapping, touch composition, profile validation
and serialization, session policies, queue storage, recovery policies and editor
models. It has no Android, Flutter, Binder, window or device access.

Platform adapters provide clocks, scheduling, storage, input events and the touch
sink. The core never reads physical devices or injects Android input itself.
`ProfileStorage` remains the persistence port; the Android `AtomicFile` adapter
stays outside this module.

The existing package names are preserved when moving source files. This keeps
serialized profiles, imports, shell entry points and protocol identities stable.
Changing a physical folder does not require changing those runtime identities.

JSON is a compile-only dependency because Android supplies `org.json`. JVM tests
use the matching JSON implementation explicitly. Bytecode targets Java 17.

Run the module's tests with `:mapper-core:test`. Android services, resources and
`SessionKeys` (the Android key-code adapter) remain outside the module.
