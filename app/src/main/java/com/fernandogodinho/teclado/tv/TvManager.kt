package com.fernandogodinho.teclado.tv

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

enum class TvLinkStatus { DISCONNECTED, CONNECTING, CONNECTED, NEEDS_PAIRING, UNREACHABLE }

data class TvPairingState(
    val tv: FoundTv,
    val step: Step,
    val error: String? = null,
) {
    enum class Step { CONNECTING, ENTER_CODE, CHECKING, DONE, FAILED }
}

/** A TV asked for text (a search box or any text field got focus). */
data class TvTextRequest(val tvName: String, val label: String, val value: String)

/**
 * Everything about typing on TVs: discovery, pairing, live sessions and the "listen" mode used
 * by [TvLinkService]. Only text typed in the "Digitar na TV" screen is ever sent; the keyboard
 * itself never talks to the network.
 */
class TvManager(private val context: Context, private val scope: CoroutineScope) {
    private val store = TvStore(context)

    private val _paired = MutableStateFlow(store.load())
    val paired: StateFlow<List<PairedTv>> = _paired.asStateFlow()

    private val _found = MutableStateFlow<List<FoundTv>>(emptyList())
    val found: StateFlow<List<FoundTv>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _pairing = MutableStateFlow<TvPairingState?>(null)
    val pairing: StateFlow<TvPairingState?> = _pairing.asStateFlow()

    private val _status = MutableStateFlow<Map<String, TvLinkStatus>>(emptyMap())
    val status: StateFlow<Map<String, TvLinkStatus>> = _status.asStateFlow()

    private val _requests = MutableSharedFlow<TvTextRequest>(extraBufferCapacity = 4)
    val requests: SharedFlow<TvTextRequest> = _requests.asSharedFlow()

    private val sessions = HashMap<String, AndroidTvSession>()
    /** TVs with a connection attempt in progress (avoids two parallel sessions to one TV). */
    private val connecting = HashSet<String>()
    private var discovery: TvDiscovery? = null
    private var pairingLink: AndroidTvPairing? = null
    private var listenJob: Job? = null
    private var lastTextSentAt = 0L
    private val lastRequestAt = HashMap<String, Long>()

    // --- Discovery ------------------------------------------------------------------------

    fun startScan() {
        if (discovery != null) return
        _scanning.value = true
        discovery = TvDiscovery(context) { list ->
            _found.value = list
            // Keep addresses of paired TVs up to date (DHCP may give them a new one).
            val byName = list.associateBy { it.name }
            val updated = _paired.value.map { tv -> byName[tv.name]?.let { tv.copy(host = it.host.hostAddress ?: tv.host) } ?: tv }
            if (updated != _paired.value) setPaired(updated)
        }.also { it.start() }
    }

    fun stopScan() {
        discovery?.stop()
        discovery = null
        _scanning.value = false
    }

    // --- Pairing --------------------------------------------------------------------------

    fun startPairing(tv: FoundTv) {
        cancelPairing()
        _pairing.value = TvPairingState(tv, TvPairingState.Step.CONNECTING)
        scope.launch(Dispatchers.IO) {
            val link = AndroidTvPairing(tv.host, clientName = "Teclado do Fernando Godinho (${Build.MODEL})")
            pairingLink = link
            runCatching { link.start() }
                .onSuccess { _pairing.value = TvPairingState(tv, TvPairingState.Step.ENTER_CODE) }
                .onFailure {
                    Log.w(TAG, "pairing start failed: ${it.javaClass.simpleName}")
                    link.close()
                    _pairing.value = TvPairingState(tv, TvPairingState.Step.FAILED, "Não foi possível falar com a TV. Ela está ligada e na mesma rede Wi-Fi?")
                }
        }
    }

    fun submitCode(code: String) {
        val state = _pairing.value ?: return
        val link = pairingLink ?: return
        _pairing.value = state.copy(step = TvPairingState.Step.CHECKING, error = null)
        scope.launch(Dispatchers.IO) {
            try {
                link.finish(code)
                link.close()
                val tv = PairedTv(state.tv.name, state.tv.host.hostAddress ?: return@launch, pin = null, pairedAt = System.currentTimeMillis())
                setPaired(_paired.value.filterNot { it.name == tv.name } + tv)
                _pairing.value = state.copy(step = TvPairingState.Step.DONE)
                connect(tv.name)
            } catch (e: WrongCodeException) {
                _pairing.value = state.copy(step = TvPairingState.Step.ENTER_CODE, error = "Código incorreto. Confira as letras e os números na tela da TV.")
            } catch (t: Throwable) {
                link.close()
                _pairing.value = state.copy(step = TvPairingState.Step.FAILED, error = "A TV encerrou o pareamento. Tente de novo.")
            }
        }
    }

    fun cancelPairing() {
        pairingLink?.close()
        pairingLink = null
        _pairing.value = null
    }

    fun forget(name: String) {
        disconnect(name)
        setPaired(_paired.value.filterNot { it.name == name })
    }

    // --- Sessions -------------------------------------------------------------------------

    fun statusOf(name: String) = _status.value[name] ?: TvLinkStatus.DISCONNECTED

    private fun setStatus(name: String, s: TvLinkStatus) = _status.update { it + (name to s) }

    fun connect(name: String) {
        val tv = _paired.value.firstOrNull { it.name == name } ?: return
        synchronized(sessions) {
            if (sessions[name] != null || !connecting.add(name)) return
        }
        setStatus(name, TvLinkStatus.CONNECTING)
        scope.launch(Dispatchers.IO) {
            try {
                openSession(tv, name)
            } finally {
                synchronized(sessions) { connecting.remove(name) }
            }
        }
    }

    private fun openSession(tv: PairedTv, name: String) {
        run {
            val address = runCatching { InetAddress.getByName(tv.host) }.getOrNull()
            if (address == null) {
                setStatus(name, TvLinkStatus.UNREACHABLE)
                return
            }
            val session = AndroidTvSession(address, tv.pin, object : AndroidTvSession.Listener {
                override fun onReady(session: AndroidTvSession) = setStatus(name, TvLinkStatus.CONNECTED)
                override fun onCertificate(session: AndroidTvSession, sha256: String) {
                    // Trust on first use, right after the user paired with the code.
                    setPaired(_paired.value.map { if (it.name == name && it.pin == null) it.copy(pin = sha256) else it })
                }
                override fun onTextFieldRequested(session: AndroidTvSession, label: String, value: String) = request(name, label, value)
                override fun onTextFieldUpdate(session: AndroidTvSession, fieldCounter: Int) {
                    // Some TVs only announce the new field through counters; ignore our own echoes.
                    if (SystemClock.elapsedRealtime() - lastTextSentAt > 3_000) request(name, "", "")
                }
                override fun onClosed(session: AndroidTvSession, error: Throwable?) {
                    synchronized(sessions) { if (sessions[name] === session) sessions.remove(name) }
                    // An established session that drops (Wi-Fi, TV turned off) only needs a reconnect.
                    setStatus(name, TvLinkStatus.DISCONNECTED)
                }
            })
            synchronized(sessions) { sessions[name] = session }
            runCatching { session.open() }.onFailure { e ->
                synchronized(sessions) { sessions.remove(name) }
                val needsPairing = e is javax.net.ssl.SSLHandshakeException || e.cause is java.security.cert.CertificateException
                setStatus(name, if (needsPairing) TvLinkStatus.NEEDS_PAIRING else TvLinkStatus.UNREACHABLE)
            }
        }
    }

    fun disconnect(name: String) {
        val s = synchronized(sessions) { sessions.remove(name) }
        s?.close()
        setStatus(name, TvLinkStatus.DISCONNECTED)
    }

    private fun session(name: String) = synchronized(sessions) { sessions[name] }

    /** Waits up to [timeoutMs] for a session to be ready; connects if needed. */
    suspend fun awaitConnected(name: String, timeoutMs: Long = 8_000): Boolean {
        if (session(name)?.ready == true) return true
        connect(name)
        return withTimeoutOrNull(timeoutMs) {
            while (isActive) {
                if (session(name)?.ready == true) return@withTimeoutOrNull true
                val st = statusOf(name)
                if (st == TvLinkStatus.NEEDS_PAIRING || st == TvLinkStatus.UNREACHABLE) return@withTimeoutOrNull false
                delay(150)
            }
            false
        } ?: false
    }

    /** Replaces the TV's focused field with [text]. */
    suspend fun setText(name: String, text: String) = withContext(Dispatchers.IO) {
        lastTextSentAt = SystemClock.elapsedRealtime()
        session(name)?.setText(text)
    }

    suspend fun key(name: String, code: Int) = withContext(Dispatchers.IO) {
        lastTextSentAt = SystemClock.elapsedRealtime()
        session(name)?.key(code)
    }

    private fun request(name: String, label: String, value: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastRequestAt[name] ?: 0L
        if (now - last < 4_000) return
        lastRequestAt[name] = now
        _requests.tryEmit(TvTextRequest(name, label, value))
    }

    // --- Listen mode (foreground service) -------------------------------------------------

    /** Keeps every paired TV connected, reconnecting with a growing pause when it is away. */
    fun startListening() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            var pause = 5_000L
            while (isActive) {
                val tvs = _paired.value
                tvs.forEach { if (session(it.name) == null) connect(it.name) }
                val anyConnected = tvs.any { statusOf(it.name) == TvLinkStatus.CONNECTED }
                pause = if (anyConnected) 15_000L else (pause * 2).coerceAtMost(120_000L)
                if (!anyConnected && tvs.isNotEmpty()) {
                    // The TV may have a new address: look for it briefly.
                    startScan()
                    delay(6_000)
                    stopScan()
                }
                delay(pause)
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _paired.value.forEach { disconnect(it.name) }
    }

    private fun setPaired(list: List<PairedTv>) {
        _paired.value = list
        runCatching { store.save(list) }
    }

    companion object {
        private const val TAG = "TvManager"
    }
}
