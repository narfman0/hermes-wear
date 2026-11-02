package dev.narfman0.hermeswear

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HermesClientTest {
    private lateinit var server: MockWebServer

    private class Config(
        override val baseUrl: String,
        override val apiKey: String = "k",
        override val conversation: String = "watch",
    ) : ServerConfig

    @Before fun up() { server = MockWebServer().also { it.start() } }
    @After fun down() { server.shutdown() }

    private fun client(key: String = "k") = HermesClient(Config(server.url("/").toString().trimEnd('/'), key))

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { it + "\n\n" })

    private fun delta(t: String) = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"$t\"}"
    private val created = "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}"
    private val done = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}"

    @Test fun streamsDeltasInOrder() = runBlocking {
        server.enqueue(sse(created, ": keepalive", delta("Hel"), delta("lo "), delta("there"), done))
        val out = client().ask("hi").toList()
        assertEquals(listOf("Hel", "lo ", "there"), out)
    }

    @Test fun sendsAuthConversationAndInput() = runBlocking {
        server.enqueue(sse(delta("x"), done))
        client().ask("what time is it").toList()
        val req = server.takeRequest()
        assertEquals("/v1/responses", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("what time is it", body["input"]!!.jsonPrimitive.content)
        assertEquals("watch", body["conversation"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
    }

    @Test fun wrongKeyIsAFriendlyError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { client().ask("hi").toList(); fail("expected error") }
        catch (e: HermesException) { assertEquals("Wrong API key", e.message) }
    }

    @Test fun truncatedStreamIsAnError() = runBlocking {
        server.enqueue(sse(delta("partial")))
        try { client().ask("hi").toList(); fail("expected error") }
        catch (e: HermesException) { assertTrue(e.message!!.contains("ended early")) }
    }

    @Test fun serverFailureEventIsSurfaced() = runBlocking {
        server.enqueue(sse("event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"model overloaded\"}}}"))
        try { client().ask("hi").toList(); fail("expected error") }
        catch (e: HermesException) { assertEquals("model overloaded", e.message) }
    }

    @Test fun unreachableServerIsAnError() = runBlocking {
        val c = HermesClient(Config("http://127.0.0.1:1"))
        try { c.ask("hi").toList(); fail("expected error") }
        catch (e: HermesException) { assertEquals("Can't reach server", e.message) }
    }

    @Test fun unconfiguredIsAnError() = runBlocking {
        try { HermesClient(Config("", "")).ask("hi").toList(); fail("expected error") }
        catch (e: HermesException) { assertTrue(e.message!!.contains("Set the server")) }
    }
}
