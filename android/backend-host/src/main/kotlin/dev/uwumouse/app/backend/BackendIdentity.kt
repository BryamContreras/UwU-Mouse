package dev.uwumouse.app.backend

/** Stable defaults retain the public app's authority and process names; previews are isolated. */
data class BackendIdentity(val applicationId: String = "dev.uwumouse.app") {
    init { require(applicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) }
    val authority: String get() = "$applicationId.backend"
    private val suffix: String get() = if(applicationId == "dev.uwumouse.app") "" else
        "_" + applicationId.substringAfterLast('.').replace(Regex("[^A-Za-z0-9_]"), "_")
    val guardianProcess: String get() = "uwumouse_backend$suffix"
    val injectorProcess: String get() = "uwumouse_injector$suffix"
}
