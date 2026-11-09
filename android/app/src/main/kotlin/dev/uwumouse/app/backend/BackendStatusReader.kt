package dev.uwumouse.app.backend

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.uwumouse.app.session.SessionController
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal typealias BackendStatusSnapshot=StatusSnapshot<SessionController.StatusIdentity,Bundle>

/** Process-wide status transport. A blocked Binder call cannot multiply across Activity recreation. */
internal object BackendStatusReader {
    private val main=Handler(Looper.getMainLooper())
    private val executor=ThreadPoolExecutor(0,1,30L,TimeUnit.SECONDS,ArrayBlockingQueue(1),
        { task -> Thread(task,"UwUBackendStatus").apply { isDaemon=true } })
    private val reader=SharedStatusRead<IBinder,BackendStatusSnapshot>(executor::execute,
        { binder ->
            val identity=SessionController.statusIdentity()
            StatusSnapshot(identity,IInjector.Stub.asInterface(binder).status())
        },
        { task -> check(main.post(task)) { "Main thread unavailable" } })

    fun request(binder:IBinder,callback:(Result<BackendStatusSnapshot>)->Unit):SharedStatusRead.Subscription? =
        reader.request(binder,callback)
}
