package dev.uwumouse.app.activation

import android.os.Bundle
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class LocalActivationActivity : ActivationFlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        current = this
        super.onCreate(savedInstanceState)
    }

    override fun getDartEntrypointFunctionName() = "activationMain"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        LocalActivation.initialize(this)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.uwumouse/control.v1").setMethodCallHandler { call, result ->
            try { if (!handleConnectionCall(call, result)) result.notImplemented() }
            catch (failure: Exception) { result.error("NATIVE_STATE", failure.message, null) }
        }
    }

    override fun onDestroy() {
        if (current === this) {
            current = null
            LocalActivation.cancel()
        }
        super.onDestroy()
    }

    companion object {
        private var current: LocalActivationActivity? = null
        internal val isOpen: Boolean get() = current != null
    }
}
