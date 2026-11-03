package com.fernandogodinho.teclado.ime

/** Why the keyboard is in private mode (shown to the user and used to pick the behaviour). */
enum class PrivateReason(val label: String) {
    NONE(""),
    PASSWORD("Campo de senha"),
    INCOGNITO("Aba anônima"),
    PRIVACY_APP("App de privacidade"),
    MANUAL("Modo privado"),
}

/**
 * Apps whose users expect nothing to be remembered: privacy browsers, secure messengers and
 * password managers. In them the keyboard switches to private mode automatically.
 *
 * Android never tells a keyboard which website is open, so sites cannot be detected; private and
 * incognito tabs of any browser are detected through the field's "no personalised learning" flag.
 */
object PrivacyApps {
    private val prefixes = listOf(
        // Privacy browsers
        "org.mozilla.focus", "org.mozilla.klar", "com.duckduckgo.mobile.android", "org.torproject.torbrowser",
        "org.torproject.android", "info.guardianproject.orfox", "com.cookiegames.smartcookie", "org.ironfoxoss.ironfox",
        "io.github.forkmaintainers.iceraven", "com.stoutner.privacybrowser", "org.mull.mull", "us.spotco.fennec_dos",
        "com.mullvad.browser", "net.waterfox.android",
        // Secure messengers
        "org.thoughtcrime.securesms", "im.molly.app", "ch.threema.app", "com.wire", "org.briarproject.briar",
        "chat.simplex.app", "im.session.android", "network.loki.messenger", "com.kaspersky.secure",
        // Password managers and vaults
        "com.x8bit.bitwarden", "com.bitwarden.authenticator", "com.onepassword.android", "com.agilebits.onepassword",
        "com.lastpass.lpandroid", "com.dashlane", "com.kunzisoft.keepass", "keepass2android", "com.keepassdroid",
        "me.proton.android.pass", "com.enpass", "com.nordpass", "com.keepersecurity",
        // Private e-mail and VPN apps
        "ch.protonmail.android", "me.proton.android", "ch.protonvpn.android", "com.tutao.tutanota", "de.tutao.tutanota",
        "net.mullvad.mullvadvpn",
    )

    fun matches(packageName: String?): Boolean {
        val pkg = packageName ?: return false
        return prefixes.any { pkg == it || pkg.startsWith("$it.") || pkg.startsWith(it) }
    }
}
