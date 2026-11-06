-keep class dev.mobimapper.mobimapper.backend.ShellMain { *; }
-keep class dev.mobimapper.mobimapper.backend.InjectionWorkerMain { *; }
-keep class dev.mobimapper.mobimapper.backend.IBackendEvents** { *; }
-keep class dev.mobimapper.mobimapper.backend.IInjector** { *; }
# Registered explicitly by JNI_OnLoad; retain exact class and method names.
-keep class dev.mobimapper.mobimapper.activation.adb.PairingContext { *; }
# Conscrypt's optional Android 4.x socket adapters are unreachable on minSdk 34.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
