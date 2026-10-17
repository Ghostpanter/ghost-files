package com.ghostpanter.files

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import java.security.Security

class GhostApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installSecurityProviders()
        graph = AppGraph(this)
        com.ghostpanter.files.core.Lang.apply(graph.settings.language)
        Thread { com.ghostpanter.files.ui.Thumbnails.trimDisk() }.start()
    }

    private fun installSecurityProviders() {
        // Conscrypt first: modern TLS (1.3, ChaCha20) for FTPS / HTTPS WebDAV on older Android.
        runCatching { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
        // Android ships a stripped "BC" provider; sshj needs the full one (X25519, Ed25519...).
        runCatching {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    companion object {
        lateinit var instance: GhostApp
            private set
        val graph: AppGraph get() = instance.graph
    }
}
