/*
 * Adapted from Shizuku, https://github.com/RikkaApps/Shizuku
 * Revision b844bc491f1790c72328e1a8e5b2349f8978f0ea (Apache-2.0).
 * Changes: standalone UwU Mouse integration, bounded networking, public Conscrypt,
 * private key persistence and reduced logging. See THIRD_PARTY_NOTICES.md.
 */
package dev.mobimapper.mobimapper.activation.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)

class AdbShellExitException(val exitCode: Int) : AdbException("ADB shell exited with code $exitCode")
