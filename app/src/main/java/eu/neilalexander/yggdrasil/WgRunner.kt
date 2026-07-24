package eu.neilalexander.yggdrasil

import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.*

class WgRunner(private val ctx: android.content.Context) : Tunnel {
    private val backend = GoBackend(ctx)
    private var _running = false
    private var _handle = -1

    override fun getName() = "yggwg"
    override fun onStateChange(newState: Tunnel.State?) {}

    fun start(endpoint: String): Boolean {
        if (_running) return true
        return try {
            val intent = GoBackend.VpnService.prepare(ctx)
            if (intent != null) { return false }

            val cfg = Config.Builder()
                .setInterface(Interface.Builder()
                    .parsePrivateKey("oGmPby5pu8/vMivvXSvoCaR/umJ6AnN86YcHqkDjO3A=")
                    .addAddress(InetNetwork.parse("10.0.0.2/32"))
                    .build())
                .addPeer(Peer.Builder()
                    .parsePublicKey("KpoDU1El5vXjdHX/muvHzjfm7IxxrZ+yZYCW6oGyux8=")
                    .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                    .setEndpoint(InetEndpoint.parse(endpoint))
                    .build())
                .build()

            backend.setState(this, Tunnel.State.UP, cfg)
            _running = true
            true
        } catch (e: Exception) {
            android.util.Log.e("WG", "start: $e")
            false
        }
    }

    fun stop() {
        if (!_running) return
        try { backend.setState(this, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        _running = false
    }

    fun isRunning(): Boolean {
        if (!_running) return false
        return try {
            backend.getState(this) == Tunnel.State.UP
        } catch (_: Exception) { false }
    }

    fun getStats(): Triple<Long, Long, Long>? {
        return try {
            val s = backend.getStatistics(this)
            Triple(s.totalRxBytes, s.totalTxBytes, s.lastHandshakeTimeEpochMillis)
        } catch (_: Exception) { null }
    }
}

data class WgStats(
    val running: Boolean,
    val rxBytes: Long,
    val txBytes: Long,
    val lastHandshakeSec: Long
)
