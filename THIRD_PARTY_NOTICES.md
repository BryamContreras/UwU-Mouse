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

Adapted files live in `android/activation-transport/src/main/kotlin/dev/mobimapper/mobimapper/activation/adb`
and `android/activation-transport/src/main/cpp/adb/adb_pairing.cpp`. Changes include the namespace,
standalone key storage, public Conscrypt provider, network timeouts, bounded
ADB payloads, JNI registration, cleanup, logging and focused protocol tests.
Neither Shizuku's service nor its application UI is included.

## TLS and SPAKE2 dependencies

| Component | Fixed version / provenance | License |
| --- | --- | --- |
| Conscrypt Android | `org.conscrypt:conscrypt-android:2.9.0`; [upstream tag](https://github.com/google/conscrypt/tree/2.9.0) | Apache 2.0, including its Netty and Harmony notices |
| Bouncy Castle PKIX, provider and utilities | `org.bouncycastle:bcpkix-jdk18on:1.86`, with `bcprov-jdk18on:1.86` and `bcutil-jdk18on:1.86`; [upstream tag](https://github.com/bcgit/bc-java/tree/r1rv86) | Bouncy Castle license (MIT style) |
| BoringSSL Android prefab | `io.github.vvb2060.ndk:boringssl:20251124`; [wrapper tag](https://github.com/vvb2060/BoringSSL_Android/tree/20251124), commit `bb1b6bf9f1e1b28f19ef1f036e6b140d980cdd11` | Notices from the linked BoringSSL source below |
| Native BoringSSL source in prefab | [commit `79048f1f1d8e6b7f9ca59b95c24486c8149122a4`](https://github.com/google/boringssl/tree/79048f1f1d8e6b7f9ca59b95c24486c8149122a4), release `0.20251124.0`; verified against the wrapper's Git submodule | Apache 2.0 and Go BSD notices in its LICENSE |

The prefab provides static `crypto_static` and `ssl_static` libraries with STL
`none` and NDK 29 metadata. Its archive includes `arm64-v8a`, `armeabi-v7a`,
`x86_64`, `x86` and `riscv64`; the app builds only its three supported release
architectures and links `crypto_static`. UwU Mouse links its small
JNI library using the NDK static C++ runtime, with hidden symbols. It does not
package a second `libc++_shared.so` or depend on LSPosed's libcxx artifact.
Conscrypt contains JNI binaries for the same three release architectures.

Downloaded Maven Central AAR SHA-256 references:

- BoringSSL 20251124: `1b49ba5dd22153ee2c1eb9b5596b346be34da03b856cabb2dceda2374d86c716`
- Conscrypt 2.9.0: `53ee06fc093b6f085c7f81da77f622823e65489cf1bdc64a87230a23603d7773`

Downloaded Bouncy Castle 1.86 JAR SHA-256 references:

- `bcpkix-jdk18on`: `8d8b41a4b149bdae8d331f059a578870954d1e3ff29f8269a9eb3ee19a7e4ef7`
- `bcprov-jdk18on`: `2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f`
- `bcutil-jdk18on`: `1c268e15f785aafb1e4670d8d6e5f856f69475e96c162dadcd1ff9b350e9139f`

Full license texts are packaged in the APK under `assets/licenses` and retained
in this repository:

- [Apache 2.0](android/activation-transport/src/main/assets/licenses/Apache-2.0.txt)
- [Conscrypt notice](android/activation-transport/src/main/assets/licenses/Conscrypt-NOTICE.txt)
- [Conscrypt Netty license](android/activation-transport/src/main/assets/licenses/LICENSE.netty.txt)
- [Conscrypt Harmony license](android/activation-transport/src/main/assets/licenses/LICENSE.harmony.txt)
- [BoringSSL notices](android/activation-transport/src/main/assets/licenses/BoringSSL.txt)
- [Bouncy Castle license](android/activation-transport/src/main/assets/licenses/Bouncy-Castle.html)

The Conscrypt notice and its referenced licenses come from the 2.9.0 source tag.
The BoringSSL notice is the LICENSE at the verified prefab source commit.
The Bouncy Castle text is extracted from `META-INF/LICENSE.md` in the actual
1.86 provider JAR and retained in the HTML asset. The APK also merges the three
Bouncy Castle JAR license resources so each distributed notice remains present.
