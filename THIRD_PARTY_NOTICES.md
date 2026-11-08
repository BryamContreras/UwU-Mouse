# Third-party notices: on-device activation

UwU Mouse embeds the following components for the Android wireless-debugging
pairing and activation flow. No Shizuku app installation is required. These
licenses cover their respective components and do not establish a license for
the rest of this repository.

## Shizuku ADB client

Source: [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku/tree/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/java/moe/shizuku/manager/adb)
and its [native pairing implementation](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/jni/adb_pairing.cpp).
Pinned revision: `b844bc491f1790c72328e1a8e5b2349f8978f0ea`.
Authors: Shizuku contributors. License: Apache License 2.0.

Adapted files live in `android/app/src/main/kotlin/dev/mobimapper/mobimapper/activation/adb`
and `android/app/src/main/cpp/adb/adb_pairing.cpp`. Changes include the namespace,
standalone key storage, public Conscrypt provider, network timeouts, bounded
ADB payloads, JNI registration, cleanup, logging and focused protocol tests.
Neither Shizuku's service nor its application UI is included.

## TLS and SPAKE2 dependencies

| Component | Fixed version / provenance | License |
| --- | --- | --- |
| Conscrypt Android | `org.conscrypt:conscrypt-android:2.5.3`; [upstream tag](https://github.com/google/conscrypt/tree/2.5.3) | Apache 2.0; bundled native BoringSSL has its own notices |
| Bouncy Castle PKIX, provider and utilities | `org.bouncycastle:bcpkix-jdk18on:1.80`, with `bcprov-jdk18on:1.80` and `bcutil-jdk18on:1.80`; [upstream tag](https://github.com/bcgit/bc-java/tree/r1rv80) | Bouncy Castle license (MIT style) |
| BoringSSL Android prefab | `io.github.vvb2060.ndk:boringssl:20250114`; [wrapper tag](https://github.com/vvb2060/BoringSSL_Android/tree/20250114), commit `20b87e2e25a793531c9cd651c629c62976b42e2b` | BoringSSL/OpenSSL/ISC/MIT notices |
| Native BoringSSL source in prefab | [commit `571a7432a19592c620fa316abde47770dad4f82b`](https://github.com/google/boringssl/tree/571a7432a19592c620fa316abde47770dad4f82b), release `0.20250114.0` | BoringSSL/OpenSSL/ISC/MIT notices |

The prefab is a static `crypto_static` library with STL `none`, NDK 28 metadata,
and includes `arm64-v8a`, `armeabi-v7a` and `x86_64`. UwU Mouse links its small
JNI library using the NDK static C++ runtime, with hidden symbols. It does not
package a second `libc++_shared.so` or depend on LSPosed's libcxx artifact.
Conscrypt contains JNI binaries for the same three release architectures.

Downloaded Maven Central AAR SHA-256 references:

- BoringSSL: `366c9af78cee572ffcfad6e8c17650646b9f79492e8177d35226a2a5569f3fbc`
- Conscrypt: `551ae4e301c571760d1791e647db6ed1dcb10d34dcae7aa12b67f220f2ce98d1`

Full license texts are packaged in the APK under `assets/licenses` and retained
in this repository:

- [Apache 2.0](android/activation-transport/src/main/assets/licenses/Apache-2.0.txt)
- [Conscrypt notice](android/activation-transport/src/main/assets/licenses/Conscrypt-NOTICE.txt)
- [BoringSSL notices](android/activation-transport/src/main/assets/licenses/BoringSSL.txt)
- [Bouncy Castle license](android/activation-transport/src/main/assets/licenses/Bouncy-Castle.html)

The OpenSSL acknowledgment required by its license is reproduced here:
“This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit. (http://www.openssl.org/)”
