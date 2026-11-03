package com.fernandogodinho.teclado.tv

import java.net.InetAddress

/** TV systems the keyboard can type on. */
enum class TvPlatform(val label: String, val pairingHint: String) {
    ANDROID_TV("Google TV / Android TV", "A TV vai mostrar um código de 6 letras e números."),
    SAMSUNG("Samsung (Tizen)", "Na TV, escolha “Permitir” quando ela perguntar se este celular pode controlá-la."),
    LG("LG (webOS)", "Na TV, aceite o pedido de conexão deste celular."),
    ROKU("Roku TV", "Se a TV recusar, ative em Configurações › Sistema › Configurações avançadas › Controle por aplicativos móveis."),
}

/** Keys the "Digitar na TV" screen can press. */
enum class TvKey { ENTER, BACK }

/** A TV announced on the local network. */
data class FoundTv(val name: String, val host: InetAddress, val port: Int, val platform: TvPlatform = TvPlatform.ANDROID_TV)

/** Callbacks shared by every TV protocol. */
interface TvSessionListener {
    fun onReady(session: TvSession)
    /** A text field got focus on the TV. */
    fun onTextFieldRequested(session: TvSession, label: String, value: String)
    fun onClosed(session: TvSession, error: Throwable?)
    /** Something to remember for next time (token, client key or certificate fingerprint). */
    fun onCredentials(session: TvSession, token: String?, pin: String?) = Unit
}

/** A live connection to one TV. */
interface TvSession {
    val ready: Boolean
    /** Opens the connection (blocking). For new TVs this is where the TV asks the user to allow it. */
    fun open()
    /** Makes the TV's focused text field show [text]. */
    fun setText(text: String)
    fun key(key: TvKey)
    fun close()
}

/** Thrown when a TV did not accept this phone (the user refused, or the TV needs pairing again). */
class TvRejectedException(message: String) : Exception(message)
