package eu.neilalexander.yggdrasil

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.OsConstants
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import eu.neilalexander.yggdrasil.YggStateReceiver.Companion.YGG_STATE_INTENT
import mobile.Yggdrasil
import org.json.JSONArray
import exitnode.Exitnode
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject


private const val TAG = "PacketTunnelProvider"
const val SERVICE_NOTIFICATION_ID = 1000

open class PacketTunnelProvider: VpnService() {
    companion object {
        const val STATE_INTENT = "eu.neilalexander.yggdrasil.PacketTunnelProvider.STATE_MESSAGE"

        const val ACTION_START = "eu.neilalexander.yggdrasil.PacketTunnelProvider.START"
        const val ACTION_STOP = "eu.neilalexander.yggdrasil.PacketTunnelProvider.STOP"
        const val ACTION_TOGGLE = "eu.neilalexander.yggdrasil.PacketTunnelProvider.TOGGLE"
        const val ACTION_CONNECT = "eu.neilalexander.yggdrasil.PacketTunnelProvider.CONNECT"
    }

    private var yggdrasil = Yggdrasil()
    private var started = AtomicBoolean()

    private lateinit var config: ConfigurationProxy

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var exitWriterThread: Thread? = null
    private var updateThread: Thread? = null
    private var peerUpdaterThread: Thread? = null

    private var parcel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "Intent is null")
            return START_NOT_STICKY
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
        return when (intent.action ?: ACTION_STOP) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping...")
                stop(); START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                Log.d(TAG, "Connecting...")
                if (started.get()) {
                    connect()
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_TOGGLE -> {
                Log.d(TAG, "Toggling...")
                if (started.get()) {
                    stop(); START_NOT_STICKY
                } else {
                    start(); START_STICKY
                }
            }
            else -> {
                if (!enabled) {
                    Log.d(TAG, "Service is disabled")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Starting...")
                start(); START_STICKY
            }
        }
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        val notification = createServiceNotification(this, State.Enabled)
        startForeground(SERVICE_NOTIFICATION_ID, notification)

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("Yggdrasil").apply {
            setReferenceCounted(false)
            acquire()
        }

        val jsonBytes = config.getJSONByteArray()
        val logJson = config.getJSON()
        if (logJson.has("PrivateKey")) logJson.put("PrivateKey", "***")
        if (logJson.has("GatewayPassword")) logJson.put("GatewayPassword", "***")
        Log.d(TAG, logJson.toString())
        val gatewayAddr = config.gatewayAddress
        val hasGateway = config.exitNodeEnabled && gatewayAddr.isNotEmpty()
        if (hasGateway) {
            yggdrasil.setProtector(object : mobile.MobileProtector {
                override fun protect(fd: Long) { protect(fd.toInt()) }
            })
        }
        yggdrasil.startJSON(jsonBytes)

        val address = yggdrasil.addressString

        val builder = Builder()
            .addAddress(address, 7)
            .addRoute("200::", 7)
            .addRoute("2000::", 128)
            .allowBypass()
            .setBlocking(true)
            .setMtu(yggdrasil.mtu.toInt())
            .setSession("Yggdrasil")

        if (hasGateway) {
            builder.addAddress("10.0.0.1", 30)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        } else {
            builder.allowFamily(OsConstants.AF_INET)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val serverString = preferences.getString(KEY_DNS_SERVERS, "")
        if (serverString!!.isNotEmpty()) {
            val servers = serverString.split(",")
            if (servers.isNotEmpty()) {
                servers.forEach {
                    Log.i(TAG, "Using DNS server $it")
                    builder.addDnsServer(it)
                }
            }
        }
        if (preferences.getBoolean(KEY_ENABLE_CHROME_FIX, false)) {
            builder.addRoute("2001:4860:4860::8888", 128)
        }

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available, retrying peers")
                if (started.get()) yggdrasil.retryPeersNow()
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback!!)

        parcel = builder.establish()
        val parcel = parcel
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            stop()
            return
        }

        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)
        if (hasGateway) {
            val port = config.gatewayPort
            val username = config.gatewayUsername
            val password = config.gatewayPassword
            Log.i(TAG, "Starting exitnode gateway=$gatewayAddr port=$port auth=${username.isNotEmpty()}")
            Exitnode.start(address, gatewayAddr, port.toLong(), yggdrasil.mtu, username, password)
            readerThread = thread { exitNodeReader() }
            writerThread = thread { writer() }
            exitWriterThread = thread { exitNodeWriter() }
        } else {
            readerThread = thread { reader() }
            writerThread = thread { writer() }
        }
        updateThread = thread {
            updater()
        }
        peerUpdaterThread = thread {
            peerUpdater()
        }

        val wgConfig = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = (phone key from /etc/wireguard/phone.key)")
            appendLine("Address = 10.0.0.2/24")
            appendLine("DNS = $gatewayAddr")
            appendLine("")
            appendLine("[Peer]")
            appendLine("PublicKey = KpoDU1El5vXjdHX/muvHzjfm7IxxrZ+yZYCW6oGyux8=")
            appendLine("Endpoint = [$address]:49638")
            appendLine("AllowedIPs = 0.0.0.0/0")
            appendLine("PersistentKeepalive = 25")
        }
        Log.i(TAG, "=== WG CONFIG (paste into WG Tunnel/WireGuard app) ===")
        Log.i(TAG, wgConfig)

        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("wg-config", wgConfig))
        Log.i(TAG, "WG config copied to clipboard")

        var intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        readerThread?.let { it.interrupt(); readerThread = null }
        writerThread?.let { it.interrupt(); writerThread = null }
        exitWriterThread?.let { it.interrupt(); exitWriterThread = null }
        updateThread?.let { it.interrupt(); updateThread = null }
        peerUpdaterThread?.let { it.interrupt(); peerUpdaterThread = null }

        networkCallback?.let {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            networkCallback = null
        }

        Exitnode.stop()
        yggdrasil.stop()

        readerStream?.let {
            it.close()
            readerStream = null
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
        parcel?.let {
            it.close()
            parcel = null
        }

        var intent = Intent(STATE_INTENT)
        intent.putExtra("type", "state")
        intent.putExtra("started", false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_DISABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        multicastLock?.release()
    }

    private fun connect() {
        if (!started.get()) {
            return
        }
        yggdrasil.retryPeersNow()
    }

    private fun updater() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            return
        }
        var lastStateUpdate = System.currentTimeMillis()
        updates@ while (started.get()) {
            val treeJSON = yggdrasil.treeJSON
            if ((application as  GlobalApplication).needUiUpdates()) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", yggdrasil.addressString)
                intent.putExtra("subnet", yggdrasil.subnetString)
                intent.putExtra("pubkey", yggdrasil.publicKeyString)
                intent.putExtra("peers", yggdrasil.peersJSON)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            val curTime = System.currentTimeMillis()
            if (lastStateUpdate + 10000 < curTime) {
                val intent = Intent(YGG_STATE_INTENT)
                var state = STATE_ENABLED
                if (yggdrasil.routingEntries > 0) {
                    state = STATE_CONNECTED
                }
                if (treeJSON != null && treeJSON != "null") {
                    val treeState = JSONArray(treeJSON)
                    val count = treeState.length()
                    if (count > 1)
                        state = STATE_CONNECTED
                }
                intent.putExtra("state", state)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                lastStateUpdate = curTime
            }

            if (Thread.currentThread().isInterrupted) {
                break@updates
            }
            if (sleep()) return
        }
    }

    private fun peerUpdater() {
        data class PeerResult(val uri: String, val latencyMs: Long, val proto: String)

        val MAX_PEERS = 5
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        var skipped = 0

        updates@ while (started.get()) {
            try {
                if (!pm.isInteractive || pm.isPowerSaveMode) {
                    skipped++
                    if (skipped < 6) {
                        Thread.sleep(60000)
                        continue
                    }
                }
                skipped = 0
                Thread.sleep(300000)
            } catch (_: InterruptedException) {
                return
            }
            try {
                val peersJson = yggdrasil.peersJSON ?: continue
                val peers = JSONArray(peersJson)
                var upCount = 0
                var downCount = 0
                val downPeers = mutableListOf<String>()
                for (i in 0 until peers.length()) {
                    val peer = peers.getJSONObject(i)
                    if (peer.getBoolean("Up")) upCount++ else {
                        downCount++
                        downPeers.add(peer.getString("URI"))
                    }
                }
                val total = upCount + downCount
                if (downCount < 1 && total >= 3) continue

                Log.i(TAG, "Peers: $upCount up, $downCount down. Scanning...")
                try {
                    val url = URL("https://publicpeers.neilalexander.dev/data/peers.json")
                    val conn = url.openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        if (conn.responseCode != HttpURLConnection.HTTP_OK) continue
                        val response = conn.inputStream.bufferedReader().readText()
                        val candidates = JSONArray(response)
                        val tested = mutableListOf<PeerResult>()

                        for (i in 0 until candidates.length()) {
                            val candidate = candidates.getJSONObject(i)
                            val location = candidate.optString("location", "")
                            val uri = candidate.optString("uri", "")
                            if (!location.contains("Europe") && !location.contains("Russia")) continue
                            if (!uri.startsWith("tcp://") && !uri.startsWith("quic://") && !uri.startsWith("tls://")) continue
                            val proto = uri.substring(0, uri.indexOf("://"))
                            val hostPort = uri.substring(uri.indexOf("://") + 3)
                            val keyIdx = hostPort.indexOf('?')
                            val addr = if (keyIdx > 0) hostPort.substring(0, keyIdx) else hostPort
                            val colonIdx = addr.lastIndexOf(':')
                            if (colonIdx <= 0 || colonIdx <= addr.lastIndexOf(']')) continue
                            val host = addr.substring(0, colonIdx)
                            val port = addr.substring(colonIdx + 1).toIntOrNull() ?: continue
                            try {
                                val start = System.currentTimeMillis()
                                Socket().use { sock ->
                                    sock.connect(InetSocketAddress(host, port), 2000)
                                    tested.add(PeerResult(uri, System.currentTimeMillis() - start, proto))
                                }
                            } catch (_: Exception) { }
                        }
                        tested.sortBy { it.latencyMs }

                        val selected = linkedSetOf<String>()
                        for (p in tested) {
                            if (p.proto == "quic" && selected.size < MAX_PEERS) selected.add(p.uri)
                        }
                        for (p in tested) {
                            if (p.proto == "tls" && selected.size < MAX_PEERS) selected.add(p.uri)
                        }
                        for (p in tested) {
                            if (p.proto == "tcp" && p.latencyMs < 500 && selected.size < MAX_PEERS) selected.add(p.uri)
                        }

                        for (uri in downPeers) try { yggdrasil.removePeer(uri) } catch (_: Exception) { }

                        var added = 0
                        for (uri in selected) {
                            try { yggdrasil.addPeer(uri); added++ } catch (_: Exception) { }
                        }
                        Log.i(TAG, "Peers updated: removed $downCount, added $added (${selected.size} from ${tested.size})")
                    } finally { conn.disconnect() }
                } catch (e: Exception) {
                    Log.e(TAG, "Peer fetch failed: $e")
                }
            } catch (e: Exception) {
                Log.e(TAG, "peerUpdater: $e")
            }
        }
    }

    private fun sleep(): Boolean {
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            return true
        }
        return false
    }

    private fun writer() {
        val buf = ByteArray(65535)
        writes@ while (started.get()) {
            val writerStream = writerStream
            val writerThread = writerThread
            if (writerThread == null || writerStream == null) {
                Log.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                Log.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            try {
                val len = yggdrasil.recvBuffer(buf)
                if (len > 0) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in write: $e")
                if (e.toString().contains("ENOBUFS")) {
                    continue
                }
                break@writes
            }
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
    }

    private fun exitNodeReader() {
        val b = ByteArray(65535)
        reads@ while (started.get()) {
            val readerStream = readerStream ?: break@reads
            if (Thread.currentThread().isInterrupted || !readerStream.fd.valid()) break@reads
            try {
                val n = readerStream.read(b)
                if (n <= 0) continue
                if (n >= 40 && (b[0].toInt() and 0xF0) == 0x60) {
                    if ((b[24].toInt() and 0xFE) == 0x02) {
                        yggdrasil.sendBuffer(b, n.toLong())
                        continue
                    }
                }
                Exitnode.sendPacket(b.copyOfRange(0, n))
            } catch (e: Exception) {
                Log.i(TAG, "Error in exitNodeReader: $e")
                break@reads
            }
        }
        readerStream?.let { it.close(); readerStream = null }
    }

    private fun exitNodeWriter() {
        writes@ while (started.get()) {
            val writerStream = writerStream ?: break@writes
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) break@writes
            try {
                val pkt = Exitnode.recvPacket() ?: break@writes
                writerStream.write(pkt)
            } catch (e: Exception) {
                Log.i(TAG, "Error in exitNodeWriter: $e")
                break@writes
            }
        }
    }

    private fun reader() {
        val b = ByteArray(65535)
        reads@ while (started.get()) {
            val readerStream = readerStream
            val readerThread = readerThread
            if (readerThread == null || readerStream == null) {
                Log.i(TAG, "Read thread or stream is null")
                break@reads
            }
            if (Thread.currentThread().isInterrupted ||!readerStream.fd.valid()) {
                Log.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            try {
                val n = readerStream.read(b)
                yggdrasil.sendBuffer(b, n.toLong())
            } catch (e: Exception) {
                Log.i(TAG, "Error in sendBuffer: $e")
                break@reads
            }
        }
        readerStream?.let {
            it.close()
            readerStream = null
        }
    }
}
