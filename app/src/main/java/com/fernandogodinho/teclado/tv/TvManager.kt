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
    /** ENTER_CODE: Google TV shows a code; WAIT_TV: Samsung/LG ask the user to allow on the TV. */
    enum class Step { CONNECTING, ENTER_CODE, WAIT_TV, CHECKING, DONE, FAILED }
}

/** A TV asked for text (a search box or any text field got focus). */
data class TvTextRequest(val tvName: String, val label: String, val value: String)

/**
 * Everything about typing on TVs: discovery, pairing, live sessions and the "listen" mode used
 * by [TvLinkService]. Works with Google TV / Android TV, Samsung (Tizen), LG (webOS) and Roku TV.
 * Only text typed in the "Digitar na TV" screen is ever sent; the keyboard itself never talks to
 * the network.
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

    private val sessions = HashMap<String, TvSession>()
    /** TVs with a connection attempt in progress (avoids two parallel sessions to one TV). */
    private val connecting = HashSet<String>()
    private var discovery: TvDiscovery? = null
    private var pairingLink: AndroidTvPairing? = null
    private var pairingJob: Job? = null
    private var listenJob: Job? = null
    private val lastRequestAt = HashMap<String, Long>()

    // --- Discovery ------------------------------------------------------------------------

    fun startScan() {
        if (discovery != null) return
        _scanning.value = true
        discovery = TvDiscovery(context) { list ->
            _found.value = list
            // Keep addresses of paired TVs up to date (DHCP may give them a new one).
            val updated = _paired.value.map { tv ->
                list.firstOrNull { it.name == tv.name && it.platform == tv.platform }
                    ?.let { tv.copy(host = it.host.hostAddress ?: tv.host) } ?: tv
            }
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
        pairingJob = scope.launch(Dispatchers.IO) {
            when (tv.platform) {
                TvPlatform.ANDROID_TV -> startCodePairing(tv)
                else -> pairByPrompt(tv)
            }
        }
    }

    /** Google TV / Android TV: the TV shows a code that the user types on the phone. */
    private fun startCodePairing(tv: FoundTv) {
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

    /** Samsung, LG and Roku: connect; the TV asks the user to allow this phone (Roku needs nothing). */
    private fun pairByPrompt(tv: FoundTv) {
        val host = tv.host.hostAddress ?: return
        var paired = PairedTv(tv.name, host, pin = null, pairedAt = System.currentTimeMillis(), platform = tv.platform)
        _pairing.value = TvPairingState(tv, if (tv.platform == TvPlatform.ROKU) TvPairingState.Step.CHECKING else TvPairingState.Step.WAIT_TV)
        val session = newSession(paired, object : TvSessionListener {
            override fun onReady(session: TvSession) = Unit
            override fun onTextFieldRequested(session: TvSession, label: String, value: String) = Unit
            override fun onClosed(session: TvSession, error: Throwable?) = Unit
            override fun onCredentials(session: TvSession, token: String?, pin: String?) {
                paired = paired.copy(token = token ?: paired.token, pin = pin ?: paired.pin)
            }
        })
        try {
            session.open()
            session.close()
            setPaired(_paired.value.filterNot { it.name == tv.name && it.platform == tv.platform } + paired)
            _pairing.value = TvPairingState(tv, TvPairingState.Step.DONE)
            connect(paired.name)
        } catch (e: TvRejectedException) {
            session.close()
            _pairing.value = TvPairingState(tv, TvPairingState.Step.FAILED, "${e.message}. Tente de novo e aceite na tela da TV.")
        } catch (t: Throwable) {
            session.close()
            Log.w(TAG, "pairing failed: ${t.javaClass.simpleName}")
            _pairing.value = TvPairingState(
                tv, TvPairingState.Step.FAILED,
                if (tv.platform == TvPlatform.ROKU) "A TV não aceitou comandos. ${TvPlatform.ROKU.pairingHint}"
                else "Não foi possível falar com a TV. Ela está ligada e na mesma rede Wi-Fi?",
            )
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
                val host = state.tv.host.hostAddress ?: return@launch
                val tv = PairedTv(state.tv.name, host, pin = null, pairedAt = System.currentTimeMillis(), platform = TvPlatform.ANDROID_TV)
                setPaired(_paired.value.filterNot { it.name == tv.name && it.platform == tv.platform } + tv)
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
        pairingJob?.cancel()
        pairingJob = null
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

    fun platformOf(name: String) = _paired.value.firstOrNull { it.name == name }?.platform

    private fun setStatus(name: String, s: TvLinkStatus) = _status.update { it + (name to s) }

    private fun newSession(tv: PairedTv, listener: TvSessionListener): TvSession {
        val address = InetAddress.getByName(tv.host)
        return when (tv.platform) {
            TvPlatform.ANDROID_TV -> AndroidTvSession(address, tv.pin, listener)
            TvPlatform.SAMSUNG -> SamsungTvSession(address, tv.token, tv.pin, listener)
            TvPlatform.LG -> LgTvSession(address, tv.token, tv.pin, listener)
            TvPlatform.ROKU -> RokuTvSession(address, listener)
        }
    }

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
        val session = runCatching {
            newSession(tv, object : TvSessionListener {
                override fun onReady(session: TvSession) = setStatus(name, TvLinkStatus.CONNECTED)
                override fun onCredentials(session: TvSession, token: String?, pin: String?) {
                    // Trust on first use (right after pairing); a new token replaces the old one.
                    setPaired(
                        _paired.value.map {
                            if (it.name != name) it else it.copy(pin = it.pin ?: pin, token = token ?: it.token)
                        },
                    )
                }
                override fun onTextFieldRequested(session: TvSession, label: String, value: String) = request(name, label, value)
                override fun onClosed(session: TvSession, error: Throwable?) {
                    synchronized(sessions) { if (sessions[name] === session) sessions.remove(name) }
                    // An established session that drops (Wi-Fi, TV turned off) only needs a reconnect.
                    setStatus(name, TvLinkStatus.DISCONNECTED)
                }
            })
        }.getOrElse {
            setStatus(name, TvLinkStatus.UNREACHABLE)
            return
        }
        synchronized(sessions) { sessions[name] = session }
        runCatching { session.open() }.onFailure { e ->
            synchronized(sessions) { sessions.remove(name) }
            session.close()
            val needsPairing = e is TvRejectedException || e is javax.net.ssl.SSLHandshakeException ||
                e.cause is java.security.cert.CertificateException
            setStatus(name, if (needsPairing) TvLinkStatus.NEEDS_PAIRING else TvLinkStatus.UNREACHABLE)
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

    /** Makes the TV's focused field show [text]. */
    suspend fun setText(name: String, text: String) = withContext(Dispatchers.IO) {
        session(name)?.setText(text)
    }

    suspend fun key(name: String, key: TvKey) = withContext(Dispatchers.IO) {
        session(name)?.key(key)
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
                // Roku cannot announce text fields, so there is nothing to listen to.
                val tvs = _paired.value.filter { it.platform != TvPlatform.ROKU }
                tvs.forEach { if (session(it.name) == null) connect(it.name) }
                val anyConnected = tvs.any { statusOf(it.name) == TvLinkStatus.CONNECTED }
                pause = if (anyConnected) 15_000L else (pause * 2).coerceAtMost(120_000L)
                if (!anyConnected && tvs.isNotEmpty()) {
                    // The TV may have a new address: look for it briefly.
                    startScan()
                    delay(8_000)
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
