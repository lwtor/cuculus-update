package dev.cuculus.update

import android.content.Context
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient

/**
 * The process-wide handle the download worker uses to reach the HTTP client the app configured.
 *
 * The worker cannot receive the client through its constructor: WorkManager reconstructs workers
 * reflectively from a persisted class name and a `(Context, WorkerParameters)` signature, so there
 * is no channel through which an object graph could be injected. [Updater.create] therefore stores
 * the client here, and the worker reads it back.
 *
 * A process restart used to lose that in-memory handle: the restored worker fell back to a plain
 * `OkHttpClient()` with no proxy and no DNS setup, which on a proxy-dependent network turns a fast
 * transfer into a slow direct connection (or a stall). [install] now also persists the client's
 * proxy as plain host/port data, so [resolve] can rebuild an equivalent client after a cold start.
 * Only the proxy endpoint is stored — no credentials, no custom SSL/DNS hooks; those must be
 * re-installed by the app's own `Application.onCreate()` when the process comes back.
 */
internal object WorkerHttp {
    private const val PREFS_NAME = "cuculus_update_http"
    private const val KEY_PROXY = "proxy"

    @Volatile
    var client: OkHttpClient? = null

    /** Called from [Updater.create] with the client the app chose (or the SDK default). */
    fun install(context: Context, client: OkHttpClient) {
        this.client = client
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROXY, encodeProxy(client.proxy))
            .apply()
    }

    /** Used by the worker, which may be restored before the app calls `Updater.create()` again. */
    fun resolve(context: Context): OkHttpClient {
        val cached = client
        if (cached != null) return cached
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROXY, "")
        val proxy = decodeProxy(encoded)
        return if (proxy == null) OkHttpClient() else OkHttpClient.Builder().proxy(proxy).build()
    }

    /**
     * `HTTP|host|port` / `SOCKS|host|port`, or blank for "no fixed proxy". Encode/decode are kept
     * free of Android dependencies so proxy round-tripping can be unit tested on the JVM.
     */
    internal fun encodeProxy(proxy: Proxy?): String {
        if (proxy == null) return ""
        val address = proxy.address() as? InetSocketAddress ?: return ""
        return "${proxy.type().name}|${address.hostString}|${address.port}"
    }

    internal fun decodeProxy(encoded: String?): Proxy? {
        if (encoded.isNullOrBlank()) return null
        val parts = encoded.split('|')
        if (parts.size != 3) return null
        val type = runCatching { Proxy.Type.valueOf(parts[0]) }.getOrNull() ?: return null
        val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return Proxy(type, InetSocketAddress.createUnresolved(parts[1], port))
    }
}
