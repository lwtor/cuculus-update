package dev.cuculus.update

import java.net.InetSocketAddress
import java.net.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Proxy persistence is plain data so it can be verified without an Android device. */
class WorkerHttpTest {

    @Test
    fun `round trips an http proxy`() {
        val proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved("127.0.0.1", 7890),
        )

        val encoded = WorkerHttp.encodeProxy(proxy)
        assertEquals("HTTP|127.0.0.1|7890", encoded)

        val decoded = WorkerHttp.decodeProxy(encoded)!!
        assertEquals(Proxy.Type.HTTP, decoded.type())
        val address = decoded.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(7890, address.port)
    }

    @Test
    fun `round trips a socks proxy`() {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("10.0.0.1", 1080))
        val decoded = WorkerHttp.decodeProxy(WorkerHttp.encodeProxy(proxy))!!
        assertEquals(Proxy.Type.SOCKS, decoded.type())
        assertEquals(1080, (decoded.address() as InetSocketAddress).port)
    }

    @Test
    fun `blank or malformed proxy means no proxy`() {
        assertEquals("", WorkerHttp.encodeProxy(null))
        assertNull(WorkerHttp.decodeProxy(null))
        assertNull(WorkerHttp.decodeProxy(""))
        assertNull(WorkerHttp.decodeProxy("HTTP|127.0.0.1"))
        assertNull(WorkerHttp.decodeProxy("HTTP|127.0.0.1|not-a-port"))
        assertNull(WorkerHttp.decodeProxy("BOGUS|127.0.0.1|7890"))
        assertNull(WorkerHttp.decodeProxy("HTTP|127.0.0.1|0"))
        assertNull(WorkerHttp.decodeProxy("HTTP|127.0.0.1|65536"))
    }
}
