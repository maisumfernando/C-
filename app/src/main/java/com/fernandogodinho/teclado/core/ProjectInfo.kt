package com.fernandogodinho.teclado.core

/** Public project locations. Update here if the GitHub repository is renamed. */
object ProjectInfo {
    const val GITHUB_OWNER = "maisumfernando"
    const val GITHUB_REPO = "C-"
    const val PAGES_HOST = "$GITHUB_OWNER.github.io"
    const val WEBSITE = "https://$PAGES_HOST/$GITHUB_REPO/"
    const val SOURCE_CODE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    /** Model catalog published with the website (validated and size-limited on download). */
    const val CATALOG_URL = "${WEBSITE}catalog/models.json"

    /** Pix key for voluntary contributions (shown in the app and on the website). */
    const val PIX_KEY = "fjgs@outlook.com"

    /** Latest released app version, published with the website. */
    const val VERSION_URL = "${WEBSITE}version.json"
}
