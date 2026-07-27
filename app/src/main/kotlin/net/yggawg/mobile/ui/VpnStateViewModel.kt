package net.yggawg.mobile.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import net.yggawg.mobile.config.AwgConfig
import net.yggawg.mobile.config.parseAwgConf
import net.yggawg.mobile.config.toConfString
import net.yggawg.mobile.peers.PeerDatabase
import net.yggawg.mobile.peers.PeerRepository
import net.yggawg.mobile.peers.models.CountryInfo
import net.yggawg.mobile.peers.models.Peer
import net.yggawg.mobile.vpn.TunnelStatus
import net.yggawg.mobile.vpn.VpnState
import net.yggawg.mobile.vpn.YggNetworkState
import net.yggawg.mobile.vpn.YggServiceAccess
import net.yggawg.mobile.vpn.YggVpnService
import net.yggawg.mobile.vpn.parseYggAddrBytes
import awgmobile.Awgmobile
import net.yggawg.mobile.AppLogger

class VpnStateViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("yggawg", Context.MODE_PRIVATE)
    private val db    = PeerDatabase.getInstance(app)
    val repo          = PeerRepository(db, app)

    // Yggdrasil private key (64 hex chars = 32 bytes seed). Generated once, persisted forever.
    val yggPrivateKey: String = loadOrGenerateYggKey()

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private val _tunnelStatus = MutableStateFlow(TunnelStatus())
    val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()

    /** Convenience derived flow — overall VPN state only. */
    val vpnState: StateFlow<VpnState> = _tunnelStatus
        .map { it.overall }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VpnState.IDLE)

    private val _awgConfig = MutableStateFlow<AwgConfig?>(null)
    val awgConfig: StateFlow<AwgConfig?> = _awgConfig.asStateFlow()

    /** Raw text of the imported .conf file; used for display so no fields are lost. */
    private val _rawConfText = MutableStateFlow<String?>(null)
    val rawConfText: StateFlow<String?> = _rawConfText.asStateFlow()

    private val _countries = MutableStateFlow<List<CountryInfo>>(emptyList())
    val countries: StateFlow<List<CountryInfo>> = _countries.asStateFlow()

    private val _currentCountryPeers = MutableStateFlow<List<Peer>>(emptyList())
    val currentCountryPeers: StateFlow<List<Peer>> = _currentCountryPeers.asStateFlow()

    private val _selectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val selectedPeers: StateFlow<Set<String>> = _selectedPeers.asStateFlow()

    private val _isLoadingPeers = MutableStateFlow(false)
    val isLoadingPeers: StateFlow<Boolean> = _isLoadingPeers.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _yggDnsEnabled = MutableStateFlow(prefs.getBoolean("ygg_dns_enabled", false))
    val yggDnsEnabled: StateFlow<Boolean> = _yggDnsEnabled.asStateFlow()

    // -------------------------------------------------------------------------
    // Broadcast receiver
    // -------------------------------------------------------------------------

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = TunnelStatus.fromIntent(intent) ?: return
            _tunnelStatus.value = status
            if (status.overall == VpnState.CONNECTED && !peersRefreshed) {
                peersRefreshed = true
                viewModelScope.launch {
                    kotlinx.coroutines.delay(5000)
                    refreshPeersFromRouter()
                }
            }
            if (status.overall != VpnState.CONNECTED) {
                peersRefreshed = false
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            statusReceiver,
            IntentFilter(YggVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        loadSavedConfig()
        loadSavedPeers()
        refreshCountries()
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(statusReceiver)
    }

    // -------------------------------------------------------------------------
    // AWG config
    // -------------------------------------------------------------------------

    /**
     * Save AWG config. [rawText] is the original .conf file content and is stored
     * separately so the display can show all lines without round-trip loss.
     */
    fun saveAwgConfig(config: AwgConfig, rawText: String) {
        _awgConfig.value = config
        _rawConfText.value = rawText
        prefs.edit()
            .putString("awg_conf", config.toConfString())   // used when starting VPN
            .putString("awg_conf_raw", rawText)              // used for display
            .apply()
    }

    private fun loadSavedConfig() {
        val parsed = prefs.getString("awg_conf", null) ?: return
        _awgConfig.value = runCatching { parseAwgConf(parsed) }.getOrNull()
        _rawConfText.value = prefs.getString("awg_conf_raw", parsed)
    }

    // -------------------------------------------------------------------------
    // VPN control
    // -------------------------------------------------------------------------

    fun connect() {
        val app = getApplication<Application>()
        val peers = _selectedPeers.value.toList()
        val intent = Intent(app, YggVpnService::class.java).apply {
            action = YggVpnService.ACTION_START
            putStringArrayListExtra(YggVpnService.EXTRA_YGG_PEERS, ArrayList(peers))
            putExtra(YggVpnService.EXTRA_YGG_KEY, yggPrivateKey)
            _awgConfig.value?.let { putExtra(YggVpnService.EXTRA_AWG_CONF, it.toConfString()) }
        }
        ContextCompat.startForegroundService(app, intent)
    }

    private fun loadOrGenerateYggKey(): String {
        val saved = prefs.getString("ygg_private_key", null)
        if (saved != null) return saved
        val hex = Awgmobile.generateYggKey()
        if (hex.isNotEmpty()) {
            prefs.edit().putString("ygg_private_key", hex).apply()
        }
        return hex.ifEmpty { "" }
    }

    fun disconnect() {
        val app = getApplication<Application>()
        app.startService(Intent(app, YggVpnService::class.java).apply {
            action = YggVpnService.ACTION_STOP
        })
    }

    fun restartAwg() {
        val app = getApplication<Application>()
        app.startService(Intent(app, YggVpnService::class.java).apply {
            action = YggVpnService.ACTION_RESTART_AWG
        })
    }

    // -------------------------------------------------------------------------
    // Peer data
    // -------------------------------------------------------------------------

    fun refreshCountries(force: Boolean = false) {
        viewModelScope.launch {
            _isLoadingPeers.value = true
            try {
                _countries.value = repo.getCountries(forceRefresh = force)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load peers: ${e.message}"
            } finally {
                _isLoadingPeers.value = false
            }
        }
    }

    fun loadPeersForCountry(countryKey: String) {
        viewModelScope.launch {
            _currentCountryPeers.value = repo.getPeersForCountry(countryKey)
        }
    }

    fun togglePeer(address: String) {
        val current = _selectedPeers.value.toMutableSet()
        if (address in current) current.remove(address) else current.add(address)
        _selectedPeers.value = current
        savePeers(current)
    }

    fun applySelectedPeers() {
        if (_tunnelStatus.value.overall == VpnState.CONNECTED ||
            _tunnelStatus.value.overall == VpnState.CONNECTING) {
            disconnect()
            connect()
        }
    }

    fun removePeer(address: String) {
        val current = _selectedPeers.value.toMutableSet()
        if (current.remove(address)) {
            _selectedPeers.value = current
            savePeers(current)
        }
    }

    fun clearError() { _errorMessage.value = null }

    fun toggleYggDns() {
        val enabled = !_yggDnsEnabled.value
        _yggDnsEnabled.value = enabled
        prefs.edit().putBoolean("ygg_dns_enabled", enabled).apply()
    }

    // -------------------------------------------------------------------------
    // Yggdrasil network
    // -------------------------------------------------------------------------

    /** Ping the AWG server's Yggdrasil address through the overlay. */
    fun pingAwgServer() {
        if (YggNetworkState.pinging.value) return
        val endpoint = _awgConfig.value?.endpoint ?: return
        // Extract IPv6 address from "[addr]:port" endpoint
        val addrBytes = parseYggAddrBytes(endpoint) ?: return
        val addrStr   = try {
            java.net.Inet6Address.getByAddress(addrBytes).hostAddress ?: return
        } catch (_: Exception) { return }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            YggNetworkState.pinging.value = true
            YggNetworkState.pingMs.value  = null
            val mgr = YggServiceAccess.manager
            val ms  = mgr?.pingYgg(addrStr)
            YggNetworkState.pingMs.value  = ms ?: -1L
            YggNetworkState.pinging.value = false
        }
    }

    private fun savePeers(peers: Set<String>) {
        prefs.edit().putStringSet("selected_peers", peers).apply()
    }

    private fun loadSavedPeers() {
        val saved = prefs.getStringSet("selected_peers", null) ?: return
        _selectedPeers.value = saved
    }

    private var peersRefreshed = false

    fun refreshPeersFromRouter() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (_tunnelStatus.value.overall != VpnState.CONNECTED) return@launch
            try {
                val url = java.net.URL("https://10.100.0.1/peers.json")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = false
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (conn.responseCode in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return@launch
                    val url2 = java.net.URL(loc)
                    val conn2 = url2.openConnection() as javax.net.ssl.HttpsURLConnection
                    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                    ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(
                        object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
                        }), java.security.SecureRandom())
                    conn2.sslSocketFactory = ctx.socketFactory
                    conn2.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    conn2.connectTimeout = 5000
                    conn2.readTimeout = 5000
                    val json2 = conn2.inputStream.bufferedReader().readText()
                    conn2.disconnect()
                    parseAndSavePeers(json2)
                } else {
                    parseAndSavePeers(json)
                }
            } catch (_: Exception) { }
        }
    }

    private fun parseAndSavePeers(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val arr = obj.getJSONArray("peers")
            val uris = (0 until arr.length()).map { arr.getJSONObject(it).getString("uri") }.toSet()
            if (uris.size < 5) return
            val merged = (_selectedPeers.value + uris).take(50)
            _selectedPeers.value = merged
            savePeers(merged)
            AppLogger.i("VpnStateViewModel", "Peers refreshed: ${uris.size} new from router")
        } catch (_: Exception) { }
    }
}
