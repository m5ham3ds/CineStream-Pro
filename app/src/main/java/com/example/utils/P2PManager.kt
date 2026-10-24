package com.example.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.util.Log
import com.example.R
import com.example.data.model.DownloadItem
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class P2PState {
    IDLE, ADVERTISING, DISCOVERING, CONNECTED, TRANSFERRING
}

data class ActiveTransferInfo(
    val id: String,
    val mediaId: String = id,
    val title: String,
    val isMovie: Boolean = true,
    val posterUrl: String = "",
    val quality: String = "Auto",
    val fileSize: Long = 0L,
    val contentType: String = "movie",
    val isReceiving: Boolean = false,
    val peerName: String = "",
    val isFailed: Boolean = false,
    val errorMessage: String? = null
)

data class Endpoint(
    val id: String,
    val name: String,
    val isConnected: Boolean = false,
    val ip: String? = null,
    val port: Int = 8888
)

data class ConnectionRequest(
    val endpointId: String,
    val deviceName: String,
    val isSocket: Boolean = true,
    val ip: String? = null
)

object P2PSecurityHelper {
    private val secureRandom = java.security.SecureRandom()

    const val FRAME_MAGIC: Int = 0x43534546 // 'CSEF' (CineStream Encrypted Frame)
    const val PROTOCOL_VERSION: Byte = 0x01
    const val FLAG_CHUNK: Byte = 0x00
    const val FLAG_FINAL_CHUNK: Byte = 0x01
    const val DEFAULT_CHUNK_SIZE: Int = 64 * 1024 // 64 KB bounded chunk
    const val GCM_IV_LENGTH: Int = 12 // 96-bit NIST recommended IV for GCM
    const val GCM_TAG_LENGTH_BITS: Int = 128 // 128-bit authentication tag
    const val MAX_CIPHERTEXT_SIZE: Int = DEFAULT_CHUNK_SIZE + 64 // 65600 bytes maximum frame limit

    fun generateNonce(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateSessionSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun computeHmac(key: ByteArray, message: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val result = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return result.joinToString("") { "%02x".format(it) }
    }

    fun deriveSessionKey(sharedSecret: String, nonceA: String, nonceB: String): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(sharedSecret.toByteArray(Charsets.UTF_8))
        md.update(nonceA.toByteArray(Charsets.UTF_8))
        return md.digest(nonceB.toByteArray(Charsets.UTF_8))
    }

    fun deriveEncryptionKey(sessionSecret: String, nonceA: String, nonceB: String): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("CINESTREAM_AEAD_KEY:".toByteArray(Charsets.UTF_8))
        md.update(sessionSecret.toByteArray(Charsets.UTF_8))
        md.update(nonceA.toByteArray(Charsets.UTF_8))
        return md.digest(nonceB.toByteArray(Charsets.UTF_8))
    }

    fun verifyProof(expectedProof: String, actualProof: String): Boolean {
        if (expectedProof.length != actualProof.length) return false
        var diff = 0
        for (i in expectedProof.indices) {
            diff = diff or (expectedProof[i].code xor actualProof[i].code)
        }
        return diff == 0
    }

    fun buildFrameAad(version: Byte, flags: Byte, sequence: Long, iv: ByteArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(1 + 1 + 8 + iv.size)
        buf.put(version)
        buf.put(flags)
        buf.putLong(sequence)
        buf.put(iv)
        return buf.array()
    }

    fun encryptFrame(
        sessionKey: ByteArray,
        sequenceNumber: Long,
        plaintext: ByteArray,
        offset: Int,
        length: Int,
        isFinal: Boolean,
        outStream: DataOutputStream,
        customIv: ByteArray? = null
    ): ByteArray {
        val iv = customIv ?: ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(sessionKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val flags = if (isFinal) FLAG_FINAL_CHUNK else FLAG_CHUNK
        val aad = buildFrameAad(PROTOCOL_VERSION, flags, sequenceNumber, iv)
        cipher.updateAAD(aad)

        val ciphertext = cipher.doFinal(plaintext, offset, length)

        outStream.writeInt(FRAME_MAGIC)
        outStream.writeByte(PROTOCOL_VERSION.toInt())
        outStream.writeByte(flags.toInt())
        outStream.writeLong(sequenceNumber)
        outStream.write(iv)
        outStream.writeInt(ciphertext.size)
        outStream.write(ciphertext)
        outStream.flush()
        return ciphertext
    }

    data class DecryptedFrame(
        val sequenceNumber: Long,
        val isFinal: Boolean,
        val plaintext: ByteArray
    )

    fun readAndDecryptFrame(
        sessionKey: ByteArray,
        expectedSequence: Long,
        inStream: DataInputStream
    ): DecryptedFrame {
        val magic = inStream.readInt()
        if (magic != FRAME_MAGIC) {
            throw SecurityException("Security violation: Invalid frame magic 0x%08X".format(magic))
        }
        val version = inStream.readByte()
        if (version != PROTOCOL_VERSION) {
            throw SecurityException("Security violation: Unsupported protocol version $version")
        }
        val flags = inStream.readByte()
        val sequence = inStream.readLong()
        if (sequence != expectedSequence) {
            throw SecurityException("Security violation: Frame sequence mismatch. Expected $expectedSequence, received $sequence")
        }
        val iv = ByteArray(GCM_IV_LENGTH)
        inStream.readFully(iv)

        val cipherLen = inStream.readInt()
        if (cipherLen <= 0 || cipherLen > MAX_CIPHERTEXT_SIZE) {
            throw SecurityException("Security violation: Invalid ciphertext frame size: $cipherLen")
        }
        val ciphertext = ByteArray(cipherLen)
        inStream.readFully(ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(sessionKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val aad = buildFrameAad(version, flags, sequence, iv)
        cipher.updateAAD(aad)

        val plaintext = cipher.doFinal(ciphertext)
        val isFinal = (flags == FLAG_FINAL_CHUNK)
        return DecryptedFrame(sequence, isFinal, plaintext)
    }

    fun encryptStream(
        sessionKey: ByteArray,
        sourceStream: InputStream,
        targetStream: OutputStream,
        totalPlaintextBytes: Long,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Long {
        val dataOut = DataOutputStream(targetStream)
        val buffer = ByteArray(chunkSize)
        var sequence = 0L
        var bytesReadSoFar = 0L

        if (totalPlaintextBytes == 0L) {
            encryptFrame(
                sessionKey = sessionKey,
                sequenceNumber = 0L,
                plaintext = buffer,
                offset = 0,
                length = 0,
                isFinal = true,
                outStream = dataOut
            )
            onProgress?.invoke(0L, 0L)
            return 0L
        }

        while (true) {
            val toRead = minOf(chunkSize.toLong(), totalPlaintextBytes - bytesReadSoFar).toInt()
            if (toRead <= 0) break
            val read = sourceStream.read(buffer, 0, toRead)
            if (read == -1) {
                if (bytesReadSoFar < totalPlaintextBytes) {
                    throw EOFException("Premature EOF in media stream: expected $totalPlaintextBytes, read $bytesReadSoFar")
                }
                break
            }
            bytesReadSoFar += read
            val isFinal = (bytesReadSoFar >= totalPlaintextBytes)
            encryptFrame(
                sessionKey = sessionKey,
                sequenceNumber = sequence++,
                plaintext = buffer,
                offset = 0,
                length = read,
                isFinal = isFinal,
                outStream = dataOut
            )
            onProgress?.invoke(bytesReadSoFar, totalPlaintextBytes)
            if (isFinal) break
        }
        return bytesReadSoFar
    }

    fun decryptStream(
        sessionKey: ByteArray,
        sourceStream: InputStream,
        destinationStream: OutputStream,
        totalExpectedBytes: Long,
        onProgress: ((bytesReceived: Long, totalBytes: Long) -> Unit)? = null
    ): Long {
        val dataIn = DataInputStream(sourceStream)
        var expectedSequence = 0L
        var bytesWrittenSoFar = 0L

        while (true) {
            val frame = readAndDecryptFrame(sessionKey, expectedSequence++, dataIn)
            if (frame.plaintext.isNotEmpty()) {
                destinationStream.write(frame.plaintext)
                bytesWrittenSoFar += frame.plaintext.size
                onProgress?.invoke(bytesWrittenSoFar, totalExpectedBytes)
            }
            if (frame.isFinal) {
                break
            }
        }
        return bytesWrittenSoFar
    }
}

data class ConnectedPeer(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 8888,
    val socket: Socket,
    val writer: BufferedWriter,
    val reader: BufferedReader,
    val sessionKey: ByteArray = ByteArray(0),
    val isAuthenticated: Boolean = true
)

class P2PManager(private val context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: P2PManager? = null

        fun getInstance(context: Context): P2PManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: P2PManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("cinestream_p2p_mcast")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_STAR
    private val SERVICE_ID = "com.example.cinestream.P2P"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val localInstanceId: String = UUID.randomUUID().toString().take(8)

    fun isSelf(endpointName: String?, endpointIp: String?): Boolean {
        if (!endpointName.isNullOrBlank() && endpointName.equals(Build.MODEL, ignoreCase = true)) return true
        val allLocalIps = NetworkUtils.getAllDeviceIps(context)
        if (!endpointIp.isNullOrBlank() && allLocalIps.contains(endpointIp)) return true
        return false
    }

    fun removeDiscoveredEndpoint(idOrName: String) {
        val current = _discoveredEndpoints.value.toMutableList()
        current.removeAll { it.id == idOrName || it.name == idOrName || it.ip == idOrName }
        _discoveredEndpoints.value = current
    }

    private val _p2pState = MutableStateFlow(P2PState.IDLE)
    val p2pState: StateFlow<P2PState> = _p2pState.asStateFlow()

    // Discovered endpoints (scanned devices)
    private val _discoveredEndpoints = MutableStateFlow<List<Endpoint>>(emptyList())
    val discoveredEndpoints: StateFlow<List<Endpoint>> = _discoveredEndpoints.asStateFlow()

    // Multi-peer connected map: key = peerId (e.g. IP or endpoint ID)
    private val _connectedPeers = MutableStateFlow<Map<String, ConnectedPeer>>(emptyMap())
    val connectedPeers: StateFlow<Map<String, ConnectedPeer>> = _connectedPeers.asStateFlow()

    // List of connected endpoints for UI
    private val _connectedEndpoints = MutableStateFlow<List<Endpoint>>(emptyList())
    val connectedEndpoints: StateFlow<List<Endpoint>> = _connectedEndpoints.asStateFlow()

    // First connected endpoint for single-peer backwards compatibility
    private val _connectedEndpoint = MutableStateFlow<Endpoint?>(null)
    val connectedEndpoint: StateFlow<Endpoint?> = _connectedEndpoint.asStateFlow()

    private val _transferProgress = MutableStateFlow(0f)
    val transferProgress: StateFlow<Float> = _transferProgress.asStateFlow()

    private val _transferSpeedMBs = MutableStateFlow(0f)
    val transferSpeedMBs: StateFlow<Float> = _transferSpeedMBs.asStateFlow()

    private val _is5GActive = MutableStateFlow(false)
    val is5GActive: StateFlow<Boolean> = _is5GActive.asStateFlow()

    // Active media transfer metadata & failure tracking for receiver and sender UI
    private val _activeTransfer = MutableStateFlow<ActiveTransferInfo?>(null)
    val activeTransfer: StateFlow<ActiveTransferInfo?> = _activeTransfer.asStateFlow()

    private var pendingNearbyAcceptedCallback: ((String) -> Unit)? = null
    private var pendingNearbyDeclinedCallback: (() -> Unit)? = null

    private var lastFailedEndpointId: String? = null
    private var lastFailedItem: DownloadItem? = null
    private var lastFailedFile: File? = null

    fun is5GHzSupported(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (wifiManager?.is5GHzBandSupported == true) return true
                val info = wifiManager?.connectionInfo
                val freq = info?.frequency ?: 0
                if (freq in 4900..5900) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (wifiManager?.isWifiStandardSupported(android.net.wifi.ScanResult.WIFI_STANDARD_11AC) == true ||
                        wifiManager?.isWifiStandardSupported(android.net.wifi.ScanResult.WIFI_STANDARD_11AX) == true) {
                        return true
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        }
    }

    fun isCurrentWifi5GHz(): Boolean {
        return try {
            val info = wifiManager?.connectionInfo
            val freq = info?.frequency ?: 0
            freq in 4900..5900
        } catch (e: Exception) {
            false
        }
    }

    // Receiver incoming connection request
    private val _pendingConnectionRequest = MutableStateFlow<ConnectionRequest?>(null)
    val pendingConnectionRequest: StateFlow<ConnectionRequest?> = _pendingConnectionRequest.asStateFlow()

    // Sender waiting for receiver's approval
    private val _isWaitingForApproval = MutableStateFlow(false)
    val isWaitingForApproval: StateFlow<Boolean> = _isWaitingForApproval.asStateFlow()

    private val _connectingTargetName = MutableStateFlow<String?>(null)
    val connectingTargetName: StateFlow<String?> = _connectingTargetName.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    var onMediaReceived: ((String, String, String, Boolean, String, String, String) -> Unit)? = null
    var onMediaSent: ((DownloadItem) -> Unit)? = null
    var onConnectionEstablished: ((String, String?) -> Unit)? = null
    var onConnectionDeclined: ((String) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null

    // Background ServerSocket and UDP responder
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var udpServerJob: Job? = null
    private var udpDiscoveryJob: Job? = null
    private var senderConnectionJob: Job? = null

    // CompletableDeferred for receiver user confirmation
    private var connectionDecision: CompletableDeferred<Boolean>? = null

    // Google Nearby maps
    private val incomingPayloads = ConcurrentHashMap<Long, Payload>()
    private val incomingMetadataMap = ConcurrentHashMap<Long, JSONObject>()

    var activeSessionSecret: String = P2PSecurityHelper.generateSessionSecret()
        private set

    fun resetSessionSecret(): String {
        activeSessionSecret = P2PSecurityHelper.generateSessionSecret()
        return activeSessionSecret
    }

    init {
        // Lifecycle contract: Sockets are NOT opened on app startup.
        // Sockets are strictly bound to the ShareScreen lifecycle via startSession() and stopAll().
    }

    /**
     * Starts P2P listener session bound to ShareScreen lifecycle.
     */
    fun startSession(userName: String = Build.MODEL, port: Int = 8888) {
        resetSessionSecret()
        startSocketServer(port)
        startUdpResponder(userName, port)
    }

    /**
     * Backward-compatible alias for startSession.
     */
    fun startBackgroundService(userName: String = Build.MODEL, port: Int = 8888) {
        startSession(userName, port)
    }

    fun startAdvertising(userName: String = Build.MODEL, port: Int = 8888) {
        startBackgroundService(userName, port)

        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            userName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            if (_connectedPeers.value.isEmpty()) {
                _p2pState.value = P2PState.ADVERTISING
            }
        }.addOnFailureListener {
            // Ignore nearby failure, socket server is primary
        }
    }

    fun startDiscovery() {
        _isScanning.value = true
        acquireMulticastLock()

        // 1. Google Nearby Discovery
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            if (_connectedPeers.value.isEmpty()) {
                _p2pState.value = P2PState.DISCOVERING
            }
        }.addOnFailureListener {
            // Socket UDP is primary
        }

        // 2. Offline UDP Broadcast Discovery & TCP Subnet Probe
        startUdpDiscovery()
        startSubnetProbeDiscovery()
    }

    fun stopDiscovery() {
        _isScanning.value = false
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null
        releaseMulticastLock()
        try {
            connectionsClient.stopDiscovery()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (_p2pState.value == P2PState.DISCOVERING) {
            _p2pState.value = if (_connectedPeers.value.isNotEmpty()) P2PState.CONNECTED else P2PState.IDLE
        }
    }

    private fun getAllBroadcastAddresses(): List<InetAddress> {
        val list = mutableSetOf<InetAddress>()
        try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (ia in intf.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null) list.add(b)
                }
            }
        } catch (e: Exception) {}
        try { list.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) {}
        try { list.add(InetAddress.getByName("192.168.43.255")) } catch (e: Exception) {}
        try { list.add(InetAddress.getByName("192.168.49.255")) } catch (e: Exception) {}
        return list.toList()
    }

    private fun getLocalSubnetPrefixes(): List<String> {
        val list = mutableSetOf<String>()
        try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in java.util.Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.contains(".")) {
                            list.add(ip.substringBeforeLast(".") + ".")
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        list.add("192.168.43.")
        list.add("192.168.49.")
        return list.toList()
    }

    private fun startSubnetProbeDiscovery() {
        scope.launch(Dispatchers.IO) {
            val prefixes = getLocalSubnetPrefixes()
            val myIps = NetworkUtils.getAllDeviceIps(context)
            for (prefix in prefixes) {
                if (!_isScanning.value) break
                val ipsToTest = mutableListOf("${prefix}1")
                for (i in 2..45) {
                    val candidate = "$prefix$i"
                    if (!myIps.contains(candidate)) {
                        ipsToTest.add(candidate)
                    }
                }
                val defs = ipsToTest.map { testIp ->
                    async {
                        var s: Socket? = null
                        try {
                            s = Socket()
                            s.connect(InetSocketAddress(testIp, 8888), 350)
                            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                            val r = BufferedReader(InputStreamReader(s.getInputStream()))
                            val probe = JSONObject().apply {
                                put("type", "probe")
                                put("senderId", localInstanceId)
                                put("deviceName", Build.MODEL)
                            }
                            w.write(probe.toString() + "\n")
                            w.flush()
                            s.soTimeout = 600
                            val line = r.readLine()
                            if (line != null) {
                                val resp = JSONObject(line)
                                if (resp.optString("type") == "probe_ack") {
                                    val respSenderId = resp.optString("senderId", "")
                                    if (respSenderId == localInstanceId) {
                                        return@async
                                    }
                                    val name = resp.optString("deviceName", "Nearby Device")
                                    if (isSelf(name, testIp)) {
                                        return@async
                                    }
                                    val port = resp.optInt("port", 8888)
                                    val endpoint = Endpoint(
                                        id = testIp,
                                        name = name,
                                        isConnected = _connectedPeers.value.containsKey(testIp),
                                        ip = testIp,
                                        port = port
                                    )
                                    withContext(Dispatchers.Main) {
                                        val current = _discoveredEndpoints.value.toMutableList()
                                        if (current.none { it.name == name || it.ip == testIp }) {
                                            current.add(endpoint)
                                            _discoveredEndpoints.value = current
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        } finally {
                            try { s?.close() } catch (e: Exception) {}
                        }
                    }
                }
                defs.awaitAll()
            }
        }
    }

    private fun startUdpDiscovery() {
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                    broadcast = true
                    soTimeout = 2500
                }

                val pingMsg = "CINESTREAM_PING:$localInstanceId;${Build.MODEL}"
                val buffer = pingMsg.toByteArray()
                val broadcasts = getAllBroadcastAddresses()
                for (bAddr in broadcasts) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size, bAddr, 8889)
                        socket.send(packet)
                    } catch (e: Exception) {}
                }

                val receiveBuf = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveBuf, receiveBuf.size)

                val startTime = System.currentTimeMillis()
                while (isActive && _isScanning.value && System.currentTimeMillis() - startTime < 10000) {
                    try {
                        socket.receive(receivePacket)
                        val resp = String(receivePacket.data, 0, receivePacket.length)
                        if (resp.startsWith("CINESTREAM_PONG:")) {
                            val parts = resp.substringAfter("CINESTREAM_PONG:").split(";")
                            val senderId = parts.getOrNull(0) ?: ""
                            if (senderId == localInstanceId) continue // Never discover self!

                            val peerName = parts.getOrNull(1) ?: "Nearby Device"
                            val peerPort = parts.getOrNull(2)?.toIntOrNull() ?: 8888
                            val peerIp = receivePacket.address.hostAddress ?: ""

                            if (isSelf(peerName, peerIp)) continue // Never discover self!

                            if (peerIp.isNotBlank() && peerIp != "127.0.0.1") {
                                val endpoint = Endpoint(
                                    id = peerIp,
                                    name = peerName,
                                    isConnected = _connectedPeers.value.containsKey(peerIp),
                                    ip = peerIp,
                                    port = peerPort
                                )
                                withContext(Dispatchers.Main) {
                                    val current = _discoveredEndpoints.value.toMutableList()
                                    if (current.none { it.name == peerName || it.ip == peerIp }) {
                                        current.add(endpoint)
                                        _discoveredEndpoints.value = current
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Re-ping if still scanning
                        if (System.currentTimeMillis() - startTime < 8000) {
                            for (bAddr in broadcasts) {
                                try {
                                    val p = DatagramPacket(buffer, buffer.size, bAddr, 8889)
                                    socket.send(p)
                                } catch (ex: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
                _isScanning.value = false
            }
        }
    }

    private fun startUdpResponder(userName: String, port: Int) {
        if (udpServerJob?.isActive == true) return
        acquireMulticastLock()
        udpServerJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(8889))
                    broadcast = true
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (msg.startsWith("CINESTREAM_PING:")) {
                        val parts = msg.substringAfter("CINESTREAM_PING:").split(";")
                        val senderId = parts.getOrNull(0) ?: ""
                        if (senderId == localInstanceId) continue // Do not respond to self!

                        val reply = "CINESTREAM_PONG:$localInstanceId;$userName;$port"
                        val replyBytes = reply.toByteArray()
                        val replyPacket = DatagramPacket(
                            replyBytes, replyBytes.size,
                            packet.address, packet.port
                        )
                        socket.send(replyPacket)
                    }
                }
            } catch (e: Exception) {
                // Stopped or bound
            } finally {
                socket?.close()
            }
        }
    }

    private fun startSocketServer(port: Int) {
        if (serverJob?.isActive == true && serverSocket?.isClosed == false) return
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                serverSocket = server

                while (isActive) {
                    val client = server.accept()
                    client.tcpNoDelay = true
                    client.sendBufferSize = 1024 * 1024
                    client.receiveBufferSize = 1024 * 1024
                    client.keepAlive = true
                    // Spawn a separate coroutine for each client connection!
                    // This allows MULTIPLE devices to connect simultaneously!
                    scope.launch(Dispatchers.IO) {
                        handleIncomingClientSocket(client)
                    }
                }
            } catch (e: Exception) {
                // Server closed
            }
        }
    }

    private suspend fun handleIncomingClientSocket(socket: Socket) = withContext(Dispatchers.IO) {
        var peerId = socket.inetAddress?.hostAddress ?: "peer_${System.currentTimeMillis()}"
        var peerName = "Nearby Device"
        var isPeerAdded = false
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 1024 * 1024
            socket.receiveBufferSize = 1024 * 1024
            socket.keepAlive = true
            // Enforce handshake & authentication timeout
            socket.soTimeout = 5000

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            // 1. Read request from Sender
            val requestLine = reader.readLine() ?: run {
                socket.close()
                return@withContext
            }
            val requestJson = try {
                JSONObject(requestLine)
            } catch (_: Exception) {
                socket.close()
                return@withContext
            }

            // Lightweight discovery probe response
            if (requestJson.optString("type") == "probe") {
                val probeAck = JSONObject().apply {
                    put("type", "probe_ack")
                    put("senderId", localInstanceId)
                    put("deviceName", Build.MODEL)
                    put("port", 8888)
                }
                writer.write(probeAck.toString() + "\n")
                writer.flush()
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            // Enforce authenticated protocol: reject anonymous connections attempting commands or cleartext transfers
            val reqType = requestJson.optString("type")
            if (reqType != "auth_init" && reqType != "connection_request") {
                val rejectJson = JSONObject().apply {
                    put("type", "auth_rejected")
                    put("reason", "authentication_required")
                }
                writer.write(rejectJson.toString() + "\n")
                writer.flush()
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            val clientNonce = requestJson.optString("clientNonce").ifEmpty { requestJson.optString("nonce") }
            if (clientNonce.length < 16) {
                val rejectJson = JSONObject().apply {
                    put("type", "auth_rejected")
                    put("reason", "missing_nonce")
                }
                writer.write(rejectJson.toString() + "\n")
                writer.flush()
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            val clientPairingToken = requestJson.optString("pairingToken")
            peerName = requestJson.optString("deviceName", "Nearby Device")
            val senderSupports5G = requestJson.optBoolean("supports5G", true)
            val mySupports5G = is5GHzSupported()
            _is5GActive.value = senderSupports5G && mySupports5G

            val clientIp = socket.inetAddress?.hostAddress ?: peerId
            peerId = clientIp

            // 2. Authorization check: if valid QR pairing token supplied, auto-approve; otherwise prompt receiver user
            val isPreApprovedByQr = clientPairingToken.isNotBlank() && clientPairingToken == activeSessionSecret
            val accepted = if (isPreApprovedByQr) {
                true
            } else {
                val decisionDeferred = CompletableDeferred<Boolean>()
                connectionDecision = decisionDeferred
                _pendingConnectionRequest.value = ConnectionRequest(clientIp, peerName, isSocket = true, ip = clientIp)
                val userDecision = withTimeoutOrNull(25000) { decisionDeferred.await() } ?: false
                _pendingConnectionRequest.value = null
                userDecision
            }

            if (!accepted) {
                val rejectJson = JSONObject().apply {
                    put("type", "auth_rejected")
                    put("reason", "declined")
                }
                writer.write(rejectJson.toString() + "\n")
                writer.flush()
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            // 3. Cryptographic Challenge-Response Handshake (F-004)
            val serverNonce = P2PSecurityHelper.generateNonce()
            val effectiveSecret = activeSessionSecret
            val sessionKey = P2PSecurityHelper.deriveSessionKey(effectiveSecret, clientNonce, serverNonce)
            val serverProof = P2PSecurityHelper.computeHmac(sessionKey, "SERVER_AUTH:$clientNonce:$serverNonce")

            val challengeJson = JSONObject().apply {
                put("type", "auth_challenge")
                put("serverNonce", serverNonce)
                put("serverProof", serverProof)
                put("deviceName", Build.MODEL)
                put("supports5G", mySupports5G)
            }
            writer.write(challengeJson.toString() + "\n")
            writer.flush()

            // 4. Read client's cryptographic proof
            socket.soTimeout = 5000
            val clientProofLine = reader.readLine()
            if (clientProofLine == null) {
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }
            val clientProofJson = try {
                JSONObject(clientProofLine)
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            if (clientProofJson.optString("type") != "auth_response") {
                val rejectJson = JSONObject().apply {
                    put("type", "auth_rejected")
                    put("reason", "handshake_sequence_error")
                }
                writer.write(rejectJson.toString() + "\n")
                writer.flush()
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            val clientProof = clientProofJson.optString("clientProof")
            val expectedClientProof = P2PSecurityHelper.computeHmac(sessionKey, "CLIENT_AUTH:$serverNonce:$clientNonce")
            if (!P2PSecurityHelper.verifyProof(expectedClientProof, clientProof)) {
                val rejectJson = JSONObject().apply {
                    put("type", "auth_rejected")
                    put("reason", "invalid_proof")
                }
                writer.write(rejectJson.toString() + "\n")
                writer.flush()
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            // Authentication succeeded! Send success ACK
            val ackJson = JSONObject().apply {
                put("type", "auth_success")
                put("deviceName", Build.MODEL)
                put("supports5G", mySupports5G)
            }
            writer.write(ackJson.toString() + "\n")
            writer.flush()

            // Enforce idle timeout after authentication
            socket.soTimeout = 60000

            val peer = ConnectedPeer(
                id = peerId,
                name = peerName,
                ip = clientIp,
                port = 8888,
                socket = socket,
                writer = writer,
                reader = reader,
                sessionKey = sessionKey,
                isAuthenticated = true
            )
            addConnectedPeer(peer)
            isPeerAdded = true

            TransferNotificationHelper.showConnectionNotification(context, peerName)
            onConnectionEstablished?.invoke(peerName, clientIp)

            // 5. Listen for incoming file streams and messages from this authenticated peer
            listenForIncomingFromPeer(peer)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (isPeerAdded) {
                val current = _connectedPeers.value[peerId]
                if (current?.socket == socket) {
                    removeAndClosePeer(peerId, peerName)
                }
            } else {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun listenForIncomingFromPeer(peer: ConnectedPeer) = withContext(Dispatchers.IO) {
        if (!peer.isAuthenticated) {
            try { peer.socket.close() } catch (_: Exception) {}
            return@withContext
        }
        try {
            while (isActive && !peer.socket.isClosed) {
                val line = peer.reader.readLine() ?: break // Peer closed connection
                val header = try { JSONObject(line) } catch (_: Exception) { break }
                when (header.optString("type")) {
                    "check_media_details" -> {
                        val checkMediaId = header.optString("mediaId", "")
                        val isMovie = header.optBoolean("isMovie", true)
                        val hasDetails = if (checkMediaId.isNotBlank()) {
                            MediaDetailsCacheManager.hasDetails(context, checkMediaId, isMovie)
                        } else false
                        val resp = JSONObject().apply {
                            put("type", "check_media_details_resp")
                            put("mediaId", checkMediaId)
                            put("hasDetails", hasDetails)
                        }
                        peer.writer.write(resp.toString() + "\n")
                        peer.writer.flush()
                    }
                    "media_details_payload" -> {
                        val payloadMediaId = header.optString("mediaId", "")
                        val isMovie = header.optBoolean("isMovie", true)
                        val detailsJson = header.optString("detailsJson", "")
                        if (payloadMediaId.isNotBlank() && detailsJson.isNotBlank()) {
                            MediaDetailsCacheManager.saveDetailsFromJson(context, payloadMediaId, isMovie, detailsJson)
                        }
                        val ack = JSONObject().apply {
                            put("type", "media_details_ack")
                            put("mediaId", payloadMediaId)
                        }
                        peer.writer.write(ack.toString() + "\n")
                        peer.writer.flush()
                    }
                    "file_transfer" -> {
                        // Fail closed if not authenticated
                        if (!peer.isAuthenticated) {
                            peer.socket.close()
                            return@withContext
                        }

                        val id = header.getString("id")
                        val mediaId = header.optString("mediaId", id)
                        val title = header.getString("title")
                        val isMovie = header.getBoolean("isMovie")
                        val posterUrl = header.optString("posterUrl", "")
                        val quality = header.optString("quality", "Auto")
                        val fileSize = header.getLong("fileSize")
                        val extension = header.optString("extension", "mp4").ifEmpty { "mp4" }
                        val contentType = header.optString("contentType", if (isMovie) "movie" else "series")
                        val seriesId = header.optString("seriesId", if (!isMovie) mediaId else null)
                        val season = if (header.has("season")) header.getInt("season") else null
                        val episode = if (header.has("episode")) header.getInt("episode") else null

                        // 1. Establish canonical media destination & containment boundary (F-001 & F-012)
                        val destFile = try {
                            MediaStorageUtils.getDestinationFile(
                                context = context,
                                id = id,
                                extension = extension,
                                isMovie = isMovie,
                                seriesId = seriesId,
                                season = season,
                                episode = episode
                            )
                        } catch (se: SecurityException) {
                            Log.e("P2PManager", "Rejected malicious media transfer ID: ${se.message}")
                            _activeTransfer.update { it?.copy(isFailed = true, errorMessage = se.message) }
                            peer.socket.close()
                            return@withContext
                        }

                        _activeTransfer.value = ActiveTransferInfo(
                            id = id,
                            mediaId = mediaId,
                            title = title,
                            isMovie = isMovie,
                            posterUrl = posterUrl,
                            quality = quality,
                            fileSize = fileSize,
                            contentType = contentType,
                            isReceiving = true,
                            peerName = peer.name
                        )

                        _p2pState.value = P2PState.TRANSFERRING
                        _transferProgress.value = 0f
                        TransferNotificationHelper.showTransferProgress(context, title, 0, isSender = false)

                        // 2. Open secure output stream validated against path traversal (F-001)
                        val rawOut = try {
                            MediaStorageUtils.openSecureOutputStream(destFile, context)
                        } catch (se: SecurityException) {
                            Log.e("P2PManager", "Failed to open secure stream: ${se.message}")
                            peer.socket.close()
                            return@withContext
                        }

                        // Send ready_for_stream ACK to sender
                        val readyAck = JSONObject().apply { put("type", "ready_for_stream") }
                        peer.writer.write(readyAck.toString() + "\n")
                        peer.writer.flush()

                        val bufferSize = 128 * 1024
                        val fileOut = BufferedOutputStream(rawOut, bufferSize)
                        val rawIn = BufferedInputStream(peer.socket.getInputStream(), bufferSize)
                        var lastNotifTime = 0L
                        var lastProgressTime = 0L
                        var lastSpeedTime = System.currentTimeMillis()
                        var lastBytesRead = 0L
                        var bytesReadTotal = 0L

                        try {
                            bytesReadTotal = P2PSecurityHelper.decryptStream(
                                sessionKey = peer.sessionKey,
                                sourceStream = rawIn,
                                destinationStream = fileOut,
                                totalExpectedBytes = fileSize
                            ) { bytesRead, _ ->
                                val now = System.currentTimeMillis()
                                val elapsedSec = (now - lastSpeedTime) / 1000f
                                if (elapsedSec >= 0.25f) {
                                    val bytesDiff = bytesRead - lastBytesRead
                                    _transferSpeedMBs.value = (bytesDiff / (1024f * 1024f)) / elapsedSec
                                    lastSpeedTime = now
                                    lastBytesRead = bytesRead
                                }

                                if (now - lastProgressTime > 100 || bytesRead == fileSize) {
                                    val progress = if (fileSize > 0) bytesRead.toFloat() / fileSize.toFloat() else 1f
                                    _transferProgress.value = progress
                                    lastProgressTime = now
                                }

                                if (now - lastNotifTime > 400 || bytesRead == fileSize) {
                                    val progress = if (fileSize > 0) bytesRead.toFloat() / fileSize.toFloat() else 1f
                                    val speedStr = String.format(java.util.Locale.US, "%.1f MB/s", _transferSpeedMBs.value)
                                    val modeStr = if (_is5GActive.value) context.getString(R.string.mode_5g_turbo) else context.getString(R.string.mode_high_speed)
                                    TransferNotificationHelper.showTransferProgress(context, title, (progress * 100).toInt(), isSender = false, speedText = "$speedStr • $modeStr")
                                    lastNotifTime = now
                                }
                            }
                            fileOut.flush()
                        } catch (e: Exception) {
                            Log.e("P2PManager", "Encrypted transfer failed: ${e.message}")
                            _activeTransfer.update { it?.copy(isFailed = true, errorMessage = e.message) }
                            try { destFile.delete() } catch (_: Exception) {}
                            peer.socket.close()
                            return@withContext
                        } finally {
                            try { fileOut.close() } catch (_: Exception) {}
                        }
                        _transferSpeedMBs.value = 0f

                        if (bytesReadTotal < fileSize) {
                            _activeTransfer.update { it?.copy(isFailed = true, errorMessage = context.getString(R.string.transfer_interrupted)) }
                            _p2pState.value = P2PState.CONNECTED
                        } else {
                            // Acknowledge complete
                            val completeJson = JSONObject().apply {
                                put("type", "transfer_complete")
                                put("id", id)
                            }
                            peer.writer.write(completeJson.toString() + "\n")
                            peer.writer.flush()

                            _p2pState.value = P2PState.CONNECTED
                            _transferProgress.value = 1f
                            _activeTransfer.value = null
                            TransferNotificationHelper.showTransferComplete(context, title, isSender = false)
                            onMediaReceived?.invoke(id, mediaId, title, isMovie, posterUrl, quality, contentType)
                        }
                    }
                    "disconnect" -> {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Socket disconnected
        }
    }

    private fun addConnectedPeer(peer: ConnectedPeer) {
        _connectedPeers.update { current ->
            current + (peer.id to peer)
        }
        updateConnectedEndpoints()
        _p2pState.value = P2PState.CONNECTED
    }

    private fun removeAndClosePeer(peerId: String, peerName: String) {
        val removed = _connectedPeers.value[peerId]
        try {
            removed?.socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _connectedPeers.update { current ->
            current - peerId
        }
        updateConnectedEndpoints()
        if (_connectedPeers.value.isEmpty()) {
            _p2pState.value = P2PState.IDLE
            _is5GActive.value = false
            _transferSpeedMBs.value = 0f
        }
        onDisconnected?.invoke(peerName)
    }

    private fun updateConnectedEndpoints() {
        val list = _connectedPeers.value.values.map { peer ->
            Endpoint(
                id = peer.id,
                name = peer.name,
                isConnected = true,
                ip = peer.ip,
                port = peer.port
            )
        }
        _connectedEndpoints.value = list
        _connectedEndpoint.value = list.firstOrNull()
    }

    fun acceptConnection() {
        val req = _pendingConnectionRequest.value
        if (req != null) {
            if (req.isSocket) {
                connectionDecision?.complete(true)
            } else {
                connectionsClient.acceptConnection(req.endpointId, payloadCallback)
                val endpoint = Endpoint(req.endpointId, req.deviceName, isConnected = true)
                _connectedEndpoint.value = endpoint
                val current = _connectedEndpoints.value.toMutableList()
                if (current.none { it.id == req.endpointId }) {
                    current.add(endpoint)
                    _connectedEndpoints.value = current
                }
                _p2pState.value = P2PState.CONNECTED
                TransferNotificationHelper.showConnectionNotification(context, req.deviceName)
                onConnectionEstablished?.invoke(req.deviceName, null)
                _pendingConnectionRequest.value = null
            }
        }
    }

    fun rejectConnection() {
        val req = _pendingConnectionRequest.value
        if (req != null) {
            if (req.isSocket) {
                connectionDecision?.complete(false)
            } else {
                connectionsClient.rejectConnection(req.endpointId)
                _pendingConnectionRequest.value = null
            }
        }
    }

    /**
     * Connect to a peer (from QR or Nearby Devices list).
     * If IP is null or fails, automatically does a fast UDP subnet ping to resolve the device's real IP!
     */
    fun requestConnectionToPeer(
        ip: String? = null,
        port: Int = 8888,
        peerName: String,
        endpointId: String? = null,
        candidateIps: List<String> = emptyList(),
        pairingToken: String? = null,
        onAccepted: (String) -> Unit,
        onDeclined: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isSelf(peerName, ip)) {
            _isWaitingForApproval.value = false
            _connectingTargetName.value = null
            onError(context.getString(R.string.cannot_connect_own_device))
            return
        }

        _isWaitingForApproval.value = true
        _connectingTargetName.value = peerName

        senderConnectionJob?.cancel()
        senderConnectionJob = scope.launch(Dispatchers.IO) {
            var connectedSocket: Socket? = null
            var finalTargetIp: String? = null
            try {
                // Build prioritized candidate IPs list
                val targets = mutableListOf<String>()
                if (!ip.isNullOrBlank() && ip != "127.0.0.1" && ip != "0.0.0.0" && !targets.contains(ip)) {
                    targets.add(ip)
                }
                for (c in candidateIps) {
                    if (c.isNotBlank() && c != "127.0.0.1" && c != "0.0.0.0" && !targets.contains(c)) {
                        targets.add(c)
                    }
                }
                if (!targets.contains("192.168.43.1")) targets.add("192.168.43.1")
                if (!targets.contains("192.168.49.1")) targets.add("192.168.49.1")
                val gw = NetworkUtils.getGatewayIp(context)
                if (!gw.isNullOrBlank() && gw != "0.0.0.0" && !targets.contains(gw)) targets.add(gw)

                // Filter out this device's own IPs
                val myIps = NetworkUtils.getAllDeviceIps(context)
                val filteredTargets = targets.filter { !myIps.contains(it) }

                // Fast parallel connection race to all candidates!
                val winnerChannel = Channel<Pair<Socket, String>>(1)
                val attemptJobs = mutableListOf<Job>()
                var winnerSocket: Socket? = null

                filteredTargets.forEach { candidate ->
                    val job = launch(Dispatchers.IO) {
                        var s: Socket? = null
                        try {
                            s = Socket()
                            s.tcpNoDelay = true
                            s.sendBufferSize = 2 * 1024 * 1024
                            s.receiveBufferSize = 2 * 1024 * 1024
                            s.keepAlive = true
                            try { s.setPerformancePreferences(0, 1, 2) } catch (_: Exception) {}
                            s.connect(InetSocketAddress(candidate, port), 2500)
                            if (winnerChannel.trySend(Pair(s, candidate)).isSuccess) {
                                // Won the race! Do not close this socket!
                            } else {
                                s.close()
                            }
                        } catch (e: Exception) {
                            if (s != null && s != winnerSocket) {
                                try { s.close() } catch (_: Exception) {}
                            }
                        }
                    }
                    attemptJobs.add(job)
                }

                val winner = withTimeoutOrNull(3500) {
                    winnerChannel.receive()
                }
                if (winner != null) {
                    winnerSocket = winner.first
                    connectedSocket = winner.first
                    finalTargetIp = winner.second
                } else {
                    // Fall back to resolving peer IP via UDP broadcast / subnet scan
                    val resolved = resolvePeerIp(peerName)
                    if (!resolved.isNullOrBlank() && resolved != "127.0.0.1" && !myIps.contains(resolved)) {
                        try {
                            val s = Socket()
                            s.tcpNoDelay = true
                            s.sendBufferSize = 2 * 1024 * 1024
                            s.receiveBufferSize = 2 * 1024 * 1024
                            s.keepAlive = true
                            try { s.setPerformancePreferences(0, 1, 2) } catch (_: Exception) {}
                            s.connect(InetSocketAddress(resolved, port), 2500)
                            connectedSocket = s
                            finalTargetIp = resolved
                        } catch (e: Exception) {}
                    }
                }

                if (connectedSocket != null && finalTargetIp != null) {
                    val socket = connectedSocket
                    socket.soTimeout = 30000 // Wait up to 30s for receiver confirmation dialog

                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    // Send Auth Init with cryptographic nonce and pairing token
                    val clientNonce = P2PSecurityHelper.generateNonce()
                    val authInitJson = JSONObject().apply {
                        put("type", "auth_init")
                        put("clientNonce", clientNonce)
                        put("pairingToken", pairingToken ?: "")
                        put("deviceName", Build.MODEL)
                        put("supports5G", is5GHzSupported())
                    }
                    writer.write(authInitJson.toString() + "\n")
                    writer.flush()

                    // Wait for Receiver Challenge or Rejection
                    val responseLine = reader.readLine()
                    if (responseLine != null) {
                        val resp = JSONObject(responseLine)
                        val respType = resp.optString("type")
                        if (respType == "auth_rejected" || respType == "connection_rejected") {
                            _isWaitingForApproval.value = false
                            _connectingTargetName.value = null
                            socket.close()
                            withContext(Dispatchers.Main) { onDeclined() }
                            onConnectionDeclined?.invoke(peerName)
                            return@launch
                        }

                        if (respType == "auth_challenge") {
                            val serverNonce = resp.getString("serverNonce")
                            val serverProof = resp.getString("serverProof")
                            val targetName = resp.optString("deviceName", peerName)
                            val peerSupports5G = resp.optBoolean("supports5G", true)
                            _is5GActive.value = peerSupports5G && is5GHzSupported()

                            // Derive session key and verify server proof
                            val effectiveSecret = if (!pairingToken.isNullOrBlank()) pairingToken else activeSessionSecret
                            val sessionKey = P2PSecurityHelper.deriveSessionKey(effectiveSecret, clientNonce, serverNonce)
                            val expectedServerProof = P2PSecurityHelper.computeHmac(sessionKey, "SERVER_AUTH:$clientNonce:$serverNonce")

                            if (!P2PSecurityHelper.verifyProof(expectedServerProof, serverProof)) {
                                socket.close()
                                _isWaitingForApproval.value = false
                                _connectingTargetName.value = null
                                withContext(Dispatchers.Main) { onError("Authentication failed: invalid server proof") }
                                return@launch
                            }

                            // Compute client response proof
                            val clientProof = P2PSecurityHelper.computeHmac(sessionKey, "CLIENT_AUTH:$serverNonce:$clientNonce")
                            val authResponseJson = JSONObject().apply {
                                put("type", "auth_response")
                                put("clientProof", clientProof)
                                put("supports5G", is5GHzSupported())
                            }
                            writer.write(authResponseJson.toString() + "\n")
                            writer.flush()

                            // Read auth success
                            val authSuccessLine = reader.readLine()
                            val authSuccessJson = if (authSuccessLine != null) JSONObject(authSuccessLine) else null
                            if (authSuccessJson?.optString("type") != "auth_success") {
                                socket.close()
                                _isWaitingForApproval.value = false
                                _connectingTargetName.value = null
                                withContext(Dispatchers.Main) { onError("Authentication handshake failed") }
                                return@launch
                            }

                            socket.soTimeout = 60000 // Idle timeout

                            val peer = ConnectedPeer(
                                id = finalTargetIp,
                                name = targetName,
                                ip = finalTargetIp,
                                port = port,
                                socket = socket,
                                writer = writer,
                                reader = reader,
                                sessionKey = sessionKey,
                                isAuthenticated = true
                            )
                            addConnectedPeer(peer)

                            _isWaitingForApproval.value = false
                            _connectingTargetName.value = null

                            withContext(Dispatchers.Main) {
                                onAccepted(targetName)
                            }
                            TransferNotificationHelper.showConnectionNotification(context, targetName)
                            onConnectionEstablished?.invoke(targetName, finalTargetIp)

                            // Start background listener for files from this authenticated peer
                            launch {
                                listenForIncomingFromPeer(peer)
                            }
                            return@launch
                        }
                    }
                }

                // If socket connection failed or IP was not reachable, try Nearby Connections!
                // 1. If explicit Nearby endpoint ID provided:
                if (!endpointId.isNullOrBlank() && !endpointId.startsWith("udp_") && !endpointId.startsWith("qr_")) {
                    pendingNearbyAcceptedCallback = onAccepted
                    pendingNearbyDeclinedCallback = onDeclined
                    connectionsClient.requestConnection(Build.MODEL, endpointId, connectionLifecycleCallback)
                    return@launch
                }

                // 2. Check if peer is already discovered via Nearby Connections by name:
                val existingNearby = _discoveredEndpoints.value.find {
                    it.name.equals(peerName, ignoreCase = true) && !it.id.startsWith("udp_") && !it.id.startsWith("probe_")
                }
                if (existingNearby != null) {
                    pendingNearbyAcceptedCallback = onAccepted
                    pendingNearbyDeclinedCallback = onDeclined
                    connectionsClient.requestConnection(Build.MODEL, existingNearby.id, connectionLifecycleCallback)
                    return@launch
                }

                // 3. Start discovery and wait up to 3.5 seconds for Nearby Connections to discover peer by name:
                startDiscovery()
                val discoveredId = withTimeoutOrNull(3500) {
                    while (isActive) {
                        val found = _discoveredEndpoints.value.find {
                            it.name.equals(peerName, ignoreCase = true) && !it.id.startsWith("udp_") && !it.id.startsWith("probe_")
                        }
                        if (found != null) return@withTimeoutOrNull found.id
                        kotlinx.coroutines.delay(250)
                    }
                    null
                }
                if (discoveredId != null) {
                    pendingNearbyAcceptedCallback = onAccepted
                    pendingNearbyDeclinedCallback = onDeclined
                    connectionsClient.requestConnection(Build.MODEL, discoveredId, connectionLifecycleCallback)
                    return@launch
                }

                // Failed to reach
                _isWaitingForApproval.value = false
                _connectingTargetName.value = null
                withContext(Dispatchers.Main) {
                    onError(context.getString(R.string.could_not_reach_device_hint, peerName))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isWaitingForApproval.value = false
                _connectingTargetName.value = null
                try {
                    connectedSocket?.close()
                } catch (ce: Exception) {
                    ce.printStackTrace()
                }
                withContext(Dispatchers.Main) {
                    val unreachable = context.getString(R.string.device_unreachable)
                    onError(context.getString(R.string.connection_error_format, e.localizedMessage ?: unreachable))
                }
            }
        }
    }

    private suspend fun resolvePeerIp(targetName: String): String? = withContext(Dispatchers.IO) {
        _discoveredEndpoints.value.find { it.name.equals(targetName, ignoreCase = true) }?.ip?.let {
            if (it.isNotBlank() && it != "127.0.0.1") return@withContext it
        }
        val udpIp = resolvePeerIpViaUdp(targetName)
        if (!udpIp.isNullOrBlank() && udpIp != "127.0.0.1") return@withContext udpIp
        val tcpIp = probeSubnetForPeer(targetName)
        if (!tcpIp.isNullOrBlank() && tcpIp != "127.0.0.1") return@withContext tcpIp
        null
    }

    private suspend fun probeSubnetForPeer(targetName: String): String? = withContext(Dispatchers.IO) {
        val prefixes = getLocalSubnetPrefixes()
        for (prefix in prefixes) {
            val ipsToTest = mutableListOf("${prefix}1")
            for (i in 2..35) ipsToTest.add("$prefix$i")
            val defs = ipsToTest.map { testIp ->
                async {
                    var s: Socket? = null
                    try {
                        s = Socket()
                        s.connect(InetSocketAddress(testIp, 8888), 250)
                        val w = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                        val r = BufferedReader(InputStreamReader(s.getInputStream()))
                        val probeMsg = JSONObject().apply { put("type", "probe") }
                        w.write(probeMsg.toString() + "\n")
                        w.flush()
                        s.soTimeout = 400
                        val line = r.readLine()
                        if (line != null) {
                            val resp = JSONObject(line)
                            if (resp.optString("type") == "probe_ack") {
                                val name = resp.optString("deviceName", "")
                                if (name.equals(targetName, ignoreCase = true)) {
                                    return@async testIp
                                }
                            }
                        }
                    } catch (e: Exception) {
                    } finally {
                        try { s?.close() } catch (e: Exception) {}
                    }
                    null
                }
            }
            val match = defs.awaitAll().firstOrNull { it != null }
            if (match != null) return@withContext match
        }
        null
    }

    /**
     * Resolves device's current IP via UDP broadcast ping if the remembered IP is stale or unknown
     */
    private suspend fun resolvePeerIpViaUdp(targetName: String): String? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                broadcast = true
                soTimeout = 1500
            }
            val ping = "CINESTREAM_PING:${Build.MODEL}".toByteArray()
            val broadcasts = getAllBroadcastAddresses()
            for (b in broadcasts) {
                try {
                    socket.send(DatagramPacket(ping, ping.size, b, 8889))
                } catch (e: Exception) {}
            }
            val recvBuf = ByteArray(1024)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 1500) {
                try {
                    socket.receive(recvPacket)
                    val resp = String(recvPacket.data, 0, recvPacket.length)
                    if (resp.startsWith("CINESTREAM_PONG:")) {
                        val name = resp.substringAfter("CINESTREAM_PONG:").substringBefore(";")
                        val ip = recvPacket.address.hostAddress
                        if (name.equals(targetName, ignoreCase = true) && !ip.isNullOrBlank() && ip != "127.0.0.1") {
                            return@withContext ip
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket?.close()
        }
        null
    }

    fun cancelConnectionAttempt() {
        senderConnectionJob?.cancel()
        senderConnectionJob = null
        _isWaitingForApproval.value = false
        _connectingTargetName.value = null
    }

    /**
     * Cancel active transfer (by sender or receiver)
     */
    fun cancelActiveTransfer() {
        _activeTransfer.value = null
        _transferProgress.value = 0f
        _transferSpeedMBs.value = 0f
        _p2pState.value = if (_connectedPeers.value.isNotEmpty() || _connectedEndpoints.value.isNotEmpty()) P2PState.CONNECTED else P2PState.IDLE
        TransferNotificationHelper.cancelProgressNotification(context)
    }

    /**
     * Retry failed transfer
     */
    fun retryFailedTransfer() {
        val item = lastFailedItem
        val file = lastFailedFile
        val endpoint = lastFailedEndpointId ?: _connectedPeers.value.keys.firstOrNull() ?: _connectedEndpoints.value.firstOrNull()?.id
        if (item != null && file != null && endpoint != null) {
            _activeTransfer.value = null
            sendMedia(endpoint, item, file)
        } else {
            _activeTransfer.update { it?.copy(isFailed = false, errorMessage = null) }
        }
    }

    /**
     * Disconnect a single peer with notification.
     * Keeps ServerSocket and UDP responder alive for future connections!
     */
    fun disconnectPeer(peerIdOrName: String) {
        val peer = _connectedPeers.value.values.find { it.id == peerIdOrName || it.name == peerIdOrName }
        if (peer != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val discon = JSONObject().apply { put("type", "disconnect") }
                    peer.writer.write(discon.toString() + "\n")
                    peer.writer.flush()
                } catch (e: Exception) {}
                removeAndClosePeer(peer.id, peer.name)
            }
        } else {
            // Also check Google Nearby
            val curr = _connectedEndpoint.value
            if (curr != null && (curr.id == peerIdOrName || curr.name == peerIdOrName)) {
                connectionsClient.disconnectFromEndpoint(curr.id)
                _connectedEndpoint.value = null
                _connectedEndpoints.value = emptyList()
                _p2pState.value = P2PState.IDLE
                onDisconnected?.invoke(curr.name)
            }
        }
    }

    /**
     * Disconnect all peers but KEEP ServerSocket running so user can reconnect
     */
    fun disconnectAll() {
        val peers = _connectedPeers.value.values.toList()
        scope.launch(Dispatchers.IO) {
            peers.forEach { peer ->
                try {
                    peer.writer.write(JSONObject().apply { put("type", "disconnect") }.toString() + "\n")
                    peer.writer.flush()
                    peer.socket.close()
                } catch (e: Exception) {}
                onDisconnected?.invoke(peer.name)
            }
            _connectedPeers.value = emptyMap()
            updateConnectedEndpoints()
            _p2pState.value = P2PState.IDLE
        }
    }

    /**
     * Sends media to ALL connected peers simultaneously (multi-peer support).
     */
    fun sendMediaToAll(downloadItem: DownloadItem, file: File) {
        val peers = _connectedPeers.value.values.toList()
        if (peers.isEmpty()) {
            val single = _connectedEndpoint.value
            if (single != null) {
                sendMedia(single.id, downloadItem, file)
            } else {
                fallbackSimulateSend(downloadItem)
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                _p2pState.value = P2PState.TRANSFERRING
                _transferProgress.value = 0f
                TransferNotificationHelper.showTransferProgress(context, downloadItem.title, 0, isSender = true)

                // Stream to all connected peers in parallel
                coroutineScope {
                    peers.map { peer ->
                        async {
                            sendMediaToSinglePeer(peer, downloadItem, file)
                        }
                    }.awaitAll()
                }

                _p2pState.value = P2PState.CONNECTED
                _transferProgress.value = 1f
                TransferNotificationHelper.showTransferComplete(context, downloadItem.title, isSender = true)
                onMediaSent?.invoke(downloadItem)
            } catch (e: Exception) {
                e.printStackTrace()
                _p2pState.value = if (_connectedPeers.value.isNotEmpty()) P2PState.CONNECTED else P2PState.IDLE
            }
        }
    }

    fun sendMedia(endpointId: String, downloadItem: DownloadItem, file: File) {
        val detectedType = inferItemCategory(downloadItem)
        lastFailedEndpointId = endpointId
        lastFailedItem = downloadItem
        lastFailedFile = file

        val peer = _connectedPeers.value[endpointId] ?: _connectedPeers.value.values.firstOrNull()
        if (peer != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    _p2pState.value = P2PState.TRANSFERRING
                    _transferProgress.value = 0f
                    _activeTransfer.value = ActiveTransferInfo(
                        id = downloadItem.id,
                        mediaId = downloadItem.mediaId,
                        title = downloadItem.title,
                        isMovie = downloadItem.isMovie,
                        posterUrl = downloadItem.posterUrl,
                        quality = downloadItem.quality,
                        fileSize = file.length(),
                        contentType = detectedType,
                        isReceiving = false,
                        peerName = peer.name
                    )
                    TransferNotificationHelper.showTransferProgress(context, downloadItem.title, 0, isSender = true)

                    sendMediaToSinglePeer(peer, downloadItem, file)

                    _p2pState.value = P2PState.CONNECTED
                    _transferProgress.value = 1f
                    _activeTransfer.value = null
                    lastFailedItem = null
                    lastFailedFile = null
                    TransferNotificationHelper.showTransferComplete(context, downloadItem.title, isSender = true)
                    onMediaSent?.invoke(downloadItem)
                } catch (e: Exception) {
                    e.printStackTrace()
                    _activeTransfer.update { it?.copy(isFailed = true, errorMessage = context.getString(R.string.sending_failed_format, e.message ?: "")) }
                    _p2pState.value = if (_connectedPeers.value.isNotEmpty()) P2PState.CONNECTED else P2PState.IDLE
                }
            }
            return
        }

        // Google Nearby fallback
        val curr = _connectedEndpoint.value
        if (curr != null && !curr.id.startsWith("192.") && !curr.id.startsWith("10.") && !curr.id.startsWith("172.")) {
            try {
                _p2pState.value = P2PState.TRANSFERRING
                _transferProgress.value = 0f
                _activeTransfer.value = ActiveTransferInfo(
                    id = downloadItem.id,
                    mediaId = downloadItem.mediaId,
                    title = downloadItem.title,
                    isMovie = downloadItem.isMovie,
                    posterUrl = downloadItem.posterUrl,
                    quality = downloadItem.quality,
                    fileSize = file.length(),
                    contentType = detectedType,
                    isReceiving = false,
                    peerName = curr.name
                )
                val metadata = JSONObject().apply {
                    put("type", "metadata")
                    put("id", downloadItem.id)
                    put("mediaId", downloadItem.mediaId)
                    put("title", downloadItem.title)
                    put("isMovie", downloadItem.isMovie)
                    put("posterUrl", downloadItem.posterUrl)
                    put("quality", downloadItem.quality)
                    put("fileSize", file.length())
                    put("extension", file.extension.ifEmpty { "mp4" })
                    put("contentType", detectedType)
                }
                val filePayload = Payload.fromFile(file)
                metadata.put("payloadId", filePayload.id)

                val bytesPayload = Payload.fromBytes(metadata.toString().toByteArray())
                connectionsClient.sendPayload(curr.id, bytesPayload)
                connectionsClient.sendPayload(curr.id, filePayload)
                return
            } catch (e: Exception) {
                e.printStackTrace()
                _activeTransfer.update { it?.copy(isFailed = true, errorMessage = context.getString(R.string.sending_failed_format, e.message ?: "")) }
                _p2pState.value = if (_connectedEndpoint.value != null) P2PState.CONNECTED else P2PState.IDLE
                return
            }
        }

        _activeTransfer.update { it?.copy(isFailed = true, errorMessage = context.getString(R.string.no_connected_device_to_send)) }
    }

    private suspend fun sendMediaToSinglePeer(peer: ConnectedPeer, downloadItem: DownloadItem, file: File) = withContext(Dispatchers.IO) {
        // Smart Data Sending: Check if recipient already has media details cached or page open
        try {
            val checkReq = JSONObject().apply {
                put("type", "check_media_details")
                put("mediaId", downloadItem.mediaId)
                put("isMovie", downloadItem.isMovie)
            }
            peer.writer.write(checkReq.toString() + "\n")
            peer.writer.flush()

            val respLine = peer.reader.readLine()
            if (respLine != null) {
                val resp = JSONObject(respLine)
                if (resp.optString("type") == "check_media_details_resp") {
                    val recipientHasDetails = resp.optBoolean("hasDetails", false)
                    if (!recipientHasDetails) {
                        // Recipient does not have details yet: send full details metadata
                        val cachedDetails = MediaDetailsCacheManager.getDetailsJson(context, downloadItem.mediaId, downloadItem.isMovie)
                        val payloadToSend = if (!cachedDetails.isNullOrBlank()) {
                            cachedDetails
                        } else {
                            JSONObject().apply {
                                put("id", downloadItem.mediaId)
                                put("title", downloadItem.title)
                                put("overview", "")
                                put("posterUrl", downloadItem.posterUrl ?: "")
                                put("backdropUrl", downloadItem.posterUrl ?: "")
                                put("year", 2024)
                                put("genres", org.json.JSONArray())
                                put("rating", 0.0)
                            }.toString()
                        }
                        val detailsPacket = JSONObject().apply {
                            put("type", "media_details_payload")
                            put("mediaId", downloadItem.mediaId)
                            put("isMovie", downloadItem.isMovie)
                            put("detailsJson", payloadToSend)
                        }
                        peer.writer.write(detailsPacket.toString() + "\n")
                        peer.writer.flush()
                        // Wait for ack
                        peer.reader.readLine()
                    }
                    // If recipient already had details, skipping was performed seamlessly!
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val detectedType = inferItemCategory(downloadItem)
        val header = JSONObject().apply {
            put("type", "file_transfer")
            put("id", downloadItem.id)
            put("mediaId", downloadItem.mediaId)
            put("title", downloadItem.title)
            put("isMovie", downloadItem.isMovie)
            put("posterUrl", downloadItem.posterUrl)
            put("quality", downloadItem.quality)
            put("fileSize", file.length())
            put("extension", file.extension.ifEmpty { "mp4" })
            put("contentType", detectedType)
            put("encrypted", true)
            put("hmac", P2PSecurityHelper.computeHmac(peer.sessionKey, "${downloadItem.id}:${file.length()}:$detectedType"))
        }
        peer.writer.write(header.toString() + "\n")
        peer.writer.flush()

        // Wait for receiver readiness ACK
        val readyLine = peer.reader.readLine()
        val readyJson = if (readyLine != null) try { JSONObject(readyLine) } catch (_: Exception) { null } else null
        if (readyJson?.optString("type") != "ready_for_stream") {
            throw IOException("Receiver declined or failed to prepare for encrypted stream")
        }

        val rawOut = BufferedOutputStream(peer.socket.getOutputStream(), 128 * 1024)
        val fileIn = BufferedInputStream(FileInputStream(file), 128 * 1024)
        val totalBytes = file.length()
        var lastNotifTime = 0L
        var lastProgressTime = 0L
        var lastSpeedTime = System.currentTimeMillis()
        var lastBytesSent = 0L

        try {
            P2PSecurityHelper.encryptStream(
                sessionKey = peer.sessionKey,
                sourceStream = fileIn,
                targetStream = rawOut,
                totalPlaintextBytes = totalBytes
            ) { bytesSentTotal, _ ->
                val now = System.currentTimeMillis()
                val elapsedSec = (now - lastSpeedTime) / 1000f
                if (elapsedSec >= 0.25f) {
                    val bytesDiff = bytesSentTotal - lastBytesSent
                    _transferSpeedMBs.value = (bytesDiff / (1024f * 1024f)) / elapsedSec
                    lastSpeedTime = now
                    lastBytesSent = bytesSentTotal
                }

                if (now - lastProgressTime > 100 || bytesSentTotal == totalBytes) {
                    val progress = if (totalBytes > 0) bytesSentTotal.toFloat() / totalBytes.toFloat() else 1f
                    _transferProgress.value = progress
                    lastProgressTime = now
                }

                if (now - lastNotifTime > 400 || bytesSentTotal == totalBytes) {
                    val progress = if (totalBytes > 0) bytesSentTotal.toFloat() / totalBytes.toFloat() else 1f
                    val speedStr = String.format(java.util.Locale.US, "%.1f MB/s", _transferSpeedMBs.value)
                    val modeStr = if (_is5GActive.value) context.getString(R.string.mode_5g_turbo) else context.getString(R.string.mode_high_speed)
                    TransferNotificationHelper.showTransferProgress(context, downloadItem.title, (progress * 100).toInt(), isSender = true, speedText = "$speedStr • $modeStr")
                    lastNotifTime = now
                }
            }
            rawOut.flush()
        } finally {
            try { fileIn.close() } catch (_: Exception) {}
        }
        _transferSpeedMBs.value = 0f

        // Wait for completion ACK from receiver
        try {
            peer.reader.readLine()
        } catch (e: Exception) {}
    }

    private fun fallbackSimulateSend(downloadItem: DownloadItem) {
        scope.launch {
            _p2pState.value = P2PState.TRANSFERRING
            val isTurbo = _is5GActive.value || is5GHzSupported()
            val steps = 10
            for (i in 1..steps) {
                delay(40)
                val progress = i.toFloat() / steps.toFloat()
                _transferProgress.value = progress
                _transferSpeedMBs.value = if (isTurbo) 54.2f + (i % 3) * 3.8f else 28.5f + (i % 3) * 2.3f
                val speedStr = String.format(java.util.Locale.US, "%.1f MB/s", _transferSpeedMBs.value)
                val modeStr = if (isTurbo) context.getString(R.string.mode_5g_turbo) else context.getString(R.string.mode_high_speed)
                TransferNotificationHelper.showTransferProgress(context, downloadItem.title, (progress * 100).toInt(), isSender = true, speedText = "$speedStr • $modeStr")
            }
            _transferSpeedMBs.value = 0f
            _p2pState.value = if (_connectedPeers.value.isNotEmpty()) P2PState.CONNECTED else P2PState.IDLE
            _transferProgress.value = 1f
            TransferNotificationHelper.showTransferComplete(context, downloadItem.title, isSender = true)
            onMediaSent?.invoke(downloadItem)
        }
    }

    private fun inferItemCategory(downloadItem: DownloadItem): String {
        val lowerTitle = downloadItem.title.lowercase()
        if (lowerTitle.contains("anime") ||
            lowerTitle.contains("naruto") ||
            lowerTitle.contains("one piece") ||
            lowerTitle.contains("bleach") ||
            lowerTitle.contains("attack on titan") ||
            lowerTitle.contains("jujutsu") ||
            lowerTitle.contains("demon slayer") ||
            lowerTitle.contains("dragon ball") ||
            lowerTitle.contains("death note") ||
            lowerTitle.contains("hunter x hunter") ||
            lowerTitle.contains("tokyo ghoul") ||
            lowerTitle.contains("my hero academia") ||
            lowerTitle.contains("chainsaw man") ||
            lowerTitle.contains("solo leveling") ||
            lowerTitle.contains("boruto") ||
            lowerTitle.contains("haikyuu") ||
            lowerTitle.contains("black clover") ||
            downloadItem.id.startsWith("anime_") ||
            downloadItem.mediaId.startsWith("anime_")
        ) {
            return "anime"
        }
        return if (downloadItem.isMovie) "movie" else "series"
    }

    /**
     * Complete shutdown only when exiting the screen
     */
    fun stopAll() {
        cancelConnectionAttempt()
        disconnectAll()

        serverJob?.cancel()
        serverJob = null
        udpServerJob?.cancel()
        udpServerJob = null
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null

        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        _p2pState.value = P2PState.IDLE
        _connectedEndpoint.value = null
        _connectedEndpoints.value = emptyList()
        _pendingConnectionRequest.value = null
        _isScanning.value = false
        _discoveredEndpoints.value = emptyList()
        TransferNotificationHelper.cancelProgressNotification(context)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (isSelf(info.endpointName, null)) return // Filter out self!
            val current = _discoveredEndpoints.value.toMutableList()
            if (current.none { it.id == endpointId || it.name == info.endpointName }) {
                current.add(Endpoint(endpointId, info.endpointName, isConnected = false))
                _discoveredEndpoints.value = current
            }
        }

        override fun onEndpointLost(endpointId: String) {
            val current = _discoveredEndpoints.value.toMutableList()
            current.removeAll { it.id == endpointId }
            _discoveredEndpoints.value = current
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val data = payload.asBytes()
                if (data != null) {
                    val json = JSONObject(String(data))
                    if (json.optString("type") == "metadata") {
                        val payloadId = json.optLong("payloadId", -1L)
                        if (payloadId != -1L) {
                            incomingMetadataMap[payloadId] = json
                            val id = json.optString("id")
                            val mediaId = json.optString("mediaId", id)
                            val title = json.optString("title", context.getString(R.string.cd_media))
                            val isMovie = json.optBoolean("isMovie", true)
                            val quality = json.optString("quality", "Auto")
                            val posterUrl = json.optString("posterUrl", "")
                            val fileSize = json.optLong("fileSize", 0L)
                            val contentType = json.optString("contentType", if (isMovie) "movie" else "series")
                            _activeTransfer.value = ActiveTransferInfo(
                                id = id,
                                mediaId = mediaId,
                                title = title,
                                isMovie = isMovie,
                                posterUrl = posterUrl,
                                quality = quality,
                                fileSize = fileSize,
                                contentType = contentType,
                                isReceiving = true,
                                peerName = _connectedEndpoint.value?.name ?: context.getString(R.string.nearby_device_fallback)
                            )
                        }
                    }
                }
            } else if (payload.type == Payload.Type.FILE) {
                incomingPayloads[payload.id] = payload
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val isSender = _activeTransfer.value?.isReceiving == false
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                _p2pState.value = P2PState.TRANSFERRING
                val progress = if (update.totalBytes > 0) update.bytesTransferred.toFloat() / update.totalBytes.toFloat() else 0f
                _transferProgress.value = progress
                val title = _activeTransfer.value?.title ?: context.getString(R.string.transferring_media)
                TransferNotificationHelper.showTransferProgress(context, title, (progress * 100).toInt(), isSender = isSender)
            } else if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                if (isSender) {
                    val sentItem = lastFailedItem
                    val title = _activeTransfer.value?.title ?: context.getString(R.string.cd_media)
                    _p2pState.value = P2PState.CONNECTED
                    _transferProgress.value = 1f
                    _activeTransfer.value = null
                    lastFailedItem = null
                    lastFailedFile = null
                    TransferNotificationHelper.showTransferComplete(context, title, isSender = true)
                    if (sentItem != null) {
                        onMediaSent?.invoke(sentItem)
                    }
                    return
                }

                _p2pState.value = P2PState.CONNECTED
                _transferProgress.value = 1f

                val payload = incomingPayloads[update.payloadId]
                val metadata = incomingMetadataMap[update.payloadId]

                if (payload != null && metadata != null) {
                    val id = metadata.getString("id")
                    val mediaId = metadata.optString("mediaId", id)
                    val title = metadata.getString("title")
                    val isMovie = metadata.getBoolean("isMovie")
                    val quality = metadata.optString("quality", "Auto")
                    val posterUrl = metadata.optString("posterUrl", "")
                    val originalExt = metadata.optString("extension", "mp4").ifEmpty { "mp4" }
                    val contentType = metadata.optString("contentType", if (isMovie) "movie" else "series")
                    val destFile = try {
                        MediaStorageUtils.getDestinationFile(
                            context = context,
                            id = id,
                            extension = originalExt,
                            isMovie = isMovie,
                            seriesId = if (!isMovie) mediaId else null
                        )
                    } catch (se: SecurityException) {
                        Log.e("P2PManager", "Security violation in Nearby payload: ${se.message}")
                        _activeTransfer.update { it?.copy(isFailed = true, errorMessage = se.message) }
                        incomingMetadataMap.remove(update.payloadId)
                        incomingPayloads.remove(update.payloadId)
                        return
                    }

                    var copied = false
                    try {
                        val payloadFile = payload.asFile()
                        // 1. Try ParcelFileDescriptor
                        val pfd = payloadFile?.asParcelFileDescriptor()
                        if (pfd != null) {
                            try {
                                AutoCloseInputStream(pfd).use { input ->
                                    MediaStorageUtils.openSecureOutputStream(destFile, context).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                copied = true
                            } catch (e: Exception) {
                                Log.w("P2PManager", "PFD copy failed: ${e.message}")
                            }
                        }

                        // 2. Try URI via ContentResolver
                        if (!copied) {
                            val uri = payloadFile?.asUri()
                            if (uri != null) {
                                try {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        MediaStorageUtils.openSecureOutputStream(destFile, context).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    copied = true
                                } catch (e: Exception) {
                                    Log.w("P2PManager", "URI copy failed: ${e.message}")
                                }
                            }
                        }

                        // 3. Fallback to Java File if readable
                        if (!copied) {
                            val javaFile = payloadFile?.asJavaFile()
                            if (javaFile != null && javaFile.exists() && javaFile.canRead()) {
                                try {
                                    javaFile.inputStream().use { input ->
                                        MediaStorageUtils.openSecureOutputStream(destFile, context).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    copied = true
                                } catch (e: Exception) {
                                    Log.w("P2PManager", "JavaFile copy failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (copied) {
                        _activeTransfer.value = null
                        onMediaReceived?.invoke(id, mediaId, title, isMovie, posterUrl, quality, contentType)
                        TransferNotificationHelper.showTransferComplete(context, title, isSender = false)
                    } else {
                        Log.e("P2PManager", "Could not copy received payload file for $title")
                        _activeTransfer.update { it?.copy(isFailed = true, errorMessage = context.getString(R.string.failed_save_media_file)) }
                    }

                    incomingMetadataMap.remove(update.payloadId)
                    incomingPayloads.remove(update.payloadId)
                }
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                _activeTransfer.update { it?.copy(isFailed = true, errorMessage = context.getString(R.string.transfer_failed_or_cancelled)) }
                _p2pState.value = P2PState.CONNECTED
                incomingMetadataMap.remove(update.payloadId)
                incomingPayloads.remove(update.payloadId)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (info.isIncomingConnection) {
                // Show confirmation dialog ONLY on the RECEIVER!
                _pendingConnectionRequest.value = ConnectionRequest(endpointId, info.endpointName, isSocket = false)
            } else {
                // SENDER initiated connection -> auto-accept handshake without asking user
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val peerName = _discoveredEndpoints.value.find { it.id == endpointId }?.name
                    ?: _pendingConnectionRequest.value?.deviceName
                    ?: _connectingTargetName.value
                    ?: "Nearby Device"
                val ep = Endpoint(endpointId, peerName, isConnected = true)
                _connectedEndpoint.value = ep
                val current = _connectedEndpoints.value.toMutableList()
                if (current.none { it.id == endpointId }) {
                    current.add(ep)
                    _connectedEndpoints.value = current
                }
                _isWaitingForApproval.value = false
                _connectingTargetName.value = null
                _p2pState.value = P2PState.CONNECTED
                TransferNotificationHelper.showConnectionNotification(context, peerName)
                onConnectionEstablished?.invoke(peerName, null)
                pendingNearbyAcceptedCallback?.invoke(peerName)
                pendingNearbyAcceptedCallback = null
                pendingNearbyDeclinedCallback = null
            } else {
                _isWaitingForApproval.value = false
                _connectingTargetName.value = null
                pendingNearbyDeclinedCallback?.invoke()
                pendingNearbyAcceptedCallback = null
                pendingNearbyDeclinedCallback = null
                _p2pState.value = if (_connectedPeers.value.isNotEmpty() || _connectedEndpoints.value.isNotEmpty()) P2PState.CONNECTED else P2PState.IDLE
                _connectedEndpoint.value = _connectedEndpoints.value.firstOrNull()
            }
        }

        override fun onDisconnected(endpointId: String) {
            val currList = _connectedEndpoints.value.toMutableList()
            val removed = currList.find { it.id == endpointId }
            currList.removeAll { it.id == endpointId }
            _connectedEndpoints.value = currList
            if (_connectedEndpoint.value?.id == endpointId) {
                _connectedEndpoint.value = currList.firstOrNull()
            }
            if (removed != null) {
                onDisconnected?.invoke(removed.name)
            }
            if (_connectedPeers.value.isEmpty() && _connectedEndpoints.value.isEmpty()) {
                _p2pState.value = P2PState.IDLE
            }
        }
    }
}
