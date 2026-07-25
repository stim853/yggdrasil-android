package net.yggawg.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

object RouterHelper {
    private const val ROUTER_IP = "192.168.10.54"
    private const val ROUTER_YGG_PORT = 63700
    const val ROUTER_PEER_URI = "tcp://$ROUTER_IP:$ROUTER_YGG_PORT"
    private const val PEERS_JSON_URL = "https://$ROUTER_IP/peers.json"

    private val trustAllCerts = run {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), java.security.SecureRandom())
        ctx
    }

    data class PeerInfo(val address: String, val latency: Int)

    suspend fun fetchTopPeers(count: Int = 10): List<PeerInfo> = withContext(Dispatchers.IO) {
        val routerPeers = fetchFromRouter()
        if (routerPeers.isNotEmpty()) return@withContext routerPeers
        fetchFromPublicNodes(count)
    }

    private suspend fun fetchFromRouter(count: Int = 10): List<PeerInfo> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(PEERS_JSON_URL).openConnection() as javax.net.ssl.HttpsURLConnection
            conn.sslSocketFactory = trustAllCerts.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val text = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(text)
            val peers = mutableListOf<PeerInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val addr = obj.optString("address", "")
                val lat = obj.optInt("latency", Int.MAX_VALUE)
                if (addr.isNotBlank()) peers.add(PeerInfo(addr, lat))
            }
            peers.sortedBy { it.latency }.take(count)
        } catch (e: Exception) {
            AppLogger.w("RouterHelper", "fetchFromRouter: $e")
            emptyList()
        }
    }

    private suspend fun fetchFromPublicNodes(count: Int = 10): List<PeerInfo> = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL("https://publicpeers.neilalexander.dev/publicnodes.json").openConnection()
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val text = conn.inputStream.bufferedReader().readText()
            val root = org.json.JSONObject(text)
            val allPeers = mutableListOf<Pair<String, Int>>()
            for (key in root.keys()) {
                val country = root.getJSONObject(key)
                for (addr in country.keys()) {
                    val info = country.getJSONObject(addr)
                    val up = info.optBoolean("up", false)
                    if (!up) continue
                    val ms = info.optInt("response_ms", Int.MAX_VALUE)
                    allPeers.add(addr to ms)
                }
            }
            allPeers.sortedBy { it.second }.take(count).map { PeerInfo(it.first, it.second) }
        } catch (e: Exception) {
            AppLogger.w("RouterHelper", "fetchFromPublicNodes: $e")
            emptyList()
        }
    }
}
