package com.raban.etabli.doc.net
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PBClientTest {
    @Test fun connectsSearchesAndPreservesTheAccountWhenReconnectionFails() = service { server, client ->
        server.enqueue(MockResponse().setBody("""{"token":"kept-secret"}"""))
        client.connect(server.url("/paperless").toString(), "user", "password")
        val login = server.takeRequest()
        assertEquals("/paperless/api/token/", login.path)
        assertEquals("password", JSONObject(login.body.readUtf8()).getString("password"))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":42,"title":"Invoice","created":null,"tags":[3]}]}"""))
        val documents = client.listDocuments(query = "budget & cost")
        assertEquals(42, documents.single().id)
        assertNull(documents.single().created)
        val request = server.takeRequest()
        assertEquals("Token kept-secret", request.getHeader("Authorization"))
        assertEquals("budget & cost", request.requestUrl!!.queryParameter("query"))
        val before = client.configFlow.first()
        server.enqueue(MockResponse().setResponseCode(401))
        try { client.connect(server.url("/wrong").toString(), "other", "bad"); fail("Bad login accepted") }
        catch (e: PBError.Http) { assertEquals(401, e.status) }
        assertEquals(before, client.configFlow.first())
    }
    @Test fun refusesMalformedOversizedRedirectAndInsecureResponses() = service { server, client ->
        server.enqueue(MockResponse().setBody("""{"token":"secret"}"""))
        client.connect(server.url("/").toString(), "user", "pass")
        server.enqueue(MockResponse().setBody("{}"))
        try { client.listDocuments(); fail("Missing results accepted") } catch (_: PBError.Decoding) { }
        server.enqueue(MockResponse().setBody(" ".repeat(MAX_JSON_BYTES + 1)))
        try { client.listDocuments(); fail("Oversized response accepted") } catch (_: PBError.Decoding) { }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.invalid/"))
        try { client.listDocuments(); fail("Redirect followed") } catch (e: PBError.Http) { assertEquals(302, e.status) }
        assertEquals(4, server.requestCount)
        try { client.connect("http://example.org", "user", "pass"); fail("Plaintext credentials allowed") } catch (_: IllegalArgumentException) { }
    }
    @Test fun cancellationClosesAnActiveResponse() = service { server, client ->
        server.enqueue(MockResponse().setBody("""{"token":"secret"}"""))
        client.connect(server.url("/").toString(), "user", "pass")
        server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"results":[]}""").setBodyDelay(2, TimeUnit.SECONDS))
        coroutineScope {
            val pending = async { client.listDocuments() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(500) { pending.cancelAndJoin() }
        }
    }

    @Test fun disconnectWinsOverAnOlderConnectionResponse() = service { server, client ->
        server.enqueue(MockResponse().setBody("""{"token":"existing-secret"}"""))
        client.connect(server.url("/").toString(), "user", "pass")
        assertNotNull(client.configFlow.first())
        server.takeRequest()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(5, TimeUnit.SECONDS)) { "Test did not release the response" }
                return MockResponse().setBody("""{"token":"late-secret"}""")
            }
        }
        coroutineScope {
            val pending = async { client.connect(server.url("/").toString(), "user", "pass") }
            try {
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
                client.disconnect()
            } finally {
                release.countDown()
            }
            val error = runCatching { pending.await() }.exceptionOrNull()
            assertTrue("The older connection should be cancelled", error is CancellationException)
            assertEquals("Account changed", error?.message)
            assertNull(client.configFlow.first())
        }
    }
}
