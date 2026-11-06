/*
 * Adapted from Shizuku, https://github.com/RikkaApps/Shizuku
 * Revision b844bc491f1790c72328e1a8e5b2349f8978f0ea (Apache-2.0).
 * Changes: standalone UwU Mouse integration, bounded networking, public Conscrypt,
 * private key persistence and reduced logging. See THIRD_PARTY_NOTICES.md.
 */
package dev.mobimapper.mobimapper.activation.adb

import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.ADB_AUTH_TOKEN
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_AUTH
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_CLSE
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_CNXN
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_MAXDATA
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_OKAY
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_OPEN
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_STLS
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_STLS_VERSION
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_VERSION
import dev.mobimapper.mobimapper.activation.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/** Blocking bootstrap transport. The owner must run this outside the input/UI threads. */
class AdbClient(private val host: String, private val port: Int, private val key: AdbKey, private val timeoutMillis: Int = 15_000) : Closeable {

    // Exists before connect(), so a cancellation can interrupt an in-flight connect.
    private val socket = Socket()
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() {
        require(port in 1..65535 && timeoutMillis in 1..60_000)
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        socket.tcpNoDelay = true
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::features=shell_v2")

        var message = read()
        if (message.command == A_STLS) {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                error("Connect to adb with TLS is not supported before Android 9")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN || message.data?.size != 20) error("Invalid ADB authentication token")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        // Legacy shell,raw still allocates a PTY. shell_v2 is necessary for actual raw IO
        // and avoids a PTY hangup killing a newly launched background app_process.
        val shell = AdbShellProtocol(listener)
        write(A_OPEN, localId, 0, "shell,v2,raw:$command")

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            shell.feed(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("not A_WRTE or A_CLSE")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("not A_OKAY or A_CLSE")
            }
        }
        shell.finish()
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
    }

    private fun read(): AdbMessage = AdbMessage.readFrom(inputStream)

    /** Thread-safe cancellation hook. May be called from outside the owner executor. */
    fun abort() {
        runCatching { socket.close() }
    }

    /** Dispose on the executor that called connect()/shellCommand(). */
    override fun close() {
        abort()
        try {
            plainInputStream.close()
        } catch (e: Throwable) {
        }
        try {
            plainOutputStream.close()
        } catch (e: Throwable) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }

        if (useTls) {
            try {
                tlsInputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsSocket.close()
            } catch (e: Exception) {
            }
        }
    }
}
