package com.openmausbot.companion.core

import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors iOS ConnectorRequestTests against the same shared fixture. */
class ConnectorRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CompanionClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CompanionClient(requireNotNull(Connection.parse(server.url("/").toString())), "paired-token")
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun messages(): Map<String, Message> =
        decodeFixture<ThreadPage>("connector-cards").messages.associateBy { it.id }

    private fun request(id: String): ConnectorRequest = requireNotNull(messages().getValue(id).connector)

    // Decoding

    @Test
    fun decodesConnectionCardsInEveryState() {
        val messages = messages()
        assertEquals(6, messages.size, "one unreadable card must not lose the page")
        assertTrue(messages.values.all { it.kind == Message.Kind.CONNECTOR })

        val required = request("conn-required")
        assertEquals("github", required.slug)
        assertEquals("GitHub", required.label)
        assertEquals(ConnectorRequest.Status.REQUIRED, required.status)
        assertEquals("resume-fixture-123", required.resumeKey)
        assertNull(messages.getValue("conn-required").from)

        val authorizing = messages.getValue("conn-authorizing")
        assertEquals(ConnectorRequest.Status.AUTHORIZING, authorizing.connector?.status)
        assertEquals("work", authorizing.connector?.alias)
        assertEquals("bot-2", authorizing.from?.botId)
        assertEquals("Connection EXPIRED", request("conn-failed").error)
        assertEquals(true, request("conn-connected").resumed)
        assertEquals(true, request("conn-dismissed").dismissed)
        assertEquals(ConnectorRequest.Status.UNKNOWN, request("conn-newer").status)
    }

    @Test
    fun aConnectorKindIsNoLongerUnknownAndEncodesBack() {
        val message = CompanionJson.decodeFromString(
            Message.serializer(),
            """{"id":"c","role":"bot","kind":"connector","at":1,"connector":{"label":"GitHub"}}""",
        )
        assertEquals(Message.Kind.CONNECTOR, message.kind)
        assertEquals("GitHub", message.connector?.displayName)
        assertEquals("G", message.connector?.initial)
        assertTrue(requireNotNull(message.connector).isPending, "no status reads as still required")
        val encoded = CompanionJson.encodeToString(Message.serializer(), message)
        assertTrue(encoded.contains("\"kind\":\"connector\""), encoded)
    }

    // Which buttons for which status

    @Test
    fun presentationFollowsDesktopsCard() {
        val required = assertNotNull(ConnectorRequestPresentation.of(request("conn-required")))
        assertEquals(ConnectorRequestPresentation.PrimaryAction.CONNECT, required.primary)
        assertTrue(required.offersNotNow)
        assertEquals(ConnectorRequestPresentation.Footer.REQUESTED, required.footer)
        assertTrue(required.showsSignInHint)
        assertFalse(required.showsConnectedBadge)

        val authorizing = assertNotNull(ConnectorRequestPresentation.of(request("conn-authorizing")))
        assertEquals(ConnectorRequestPresentation.PrimaryAction.OPEN_AGAIN, authorizing.primary)
        assertTrue(authorizing.offersNotNow)
        assertEquals(ConnectorRequestPresentation.Footer.WAITING, authorizing.footer)

        val failed = assertNotNull(ConnectorRequestPresentation.of(request("conn-failed")))
        assertEquals(ConnectorRequestPresentation.PrimaryAction.TRY_AGAIN, failed.primary)
        assertTrue(failed.offersNotNow)

        val resumed = assertNotNull(ConnectorRequestPresentation.of(request("conn-connected")))
        assertNull(resumed.primary)
        assertFalse(resumed.offersNotNow)
        assertTrue(resumed.showsContinuing)
        assertEquals(ConnectorRequestPresentation.Body.RESUMED, resumed.body)
        assertEquals(ConnectorRequestPresentation.Footer.READY_TO_USE, resumed.footer)

        val paused = assertNotNull(ConnectorRequestPresentation.of(request("conn-connected").copy(resumed = false)))
        assertEquals(ConnectorRequestPresentation.PrimaryAction.CONTINUE_TASK, paused.primary)
        assertEquals(ConnectorRequestPresentation.Body.PAUSED, paused.body)
        assertTrue(paused.showsConnectedBadge)
        assertFalse(paused.showsContinuing)

        assertNull(ConnectorRequestPresentation.of(request("conn-dismissed")), "a request set aside is not drawn")

        val newer = assertNotNull(ConnectorRequestPresentation.of(request("conn-newer")))
        assertNull(newer.primary, "a state this build does not know offers nothing to tap")
        assertFalse(newer.offersNotNow)
    }

    @Test
    fun pollsOnlyWhileASignInPageIsOpen() {
        assertTrue(request("conn-authorizing").pollsStatus)
        for (id in listOf("conn-required", "conn-failed", "conn-connected", "conn-newer")) {
            assertFalse(request(id).pollsStatus, id)
        }
        assertFalse(request("conn-authorizing").copy(dismissed = true).pollsStatus)
        assertEquals(75, ConnectorRequestPolling.MAXIMUM_CHECKS)
        assertEquals(4_000L, ConnectorRequestPolling.INTERVAL_MILLIS)
    }

    @Test
    fun reusesASignInLinkForTenMinutesOnly() {
        val link = ConnectorAuthorizationLink(URI("https://connect.composio.dev/x"), createdAtMillis = 1_000_000)
        assertEquals(link.url, link.reusable(1_000_000))
        assertEquals(link.url, link.reusable(1_000_000 + 599_999))
        assertNull(link.reusable(1_000_000 + 600_000))
        assertNull(link.reusable(999_999), "a clock that went backwards is not trusted")
    }

    // Owner, preview and transcript

    @Test
    fun actsForTheChatsBotOrTheRoomMemberThatAsked() {
        val bot = CompanionJson.decodeFromString(
            Bot.serializer(),
            """{"id":"bot-1","threadId":"t1","name":"Pepper","title":"","description":"","notifications":true,
               "color":"blue","unread":false,"createdAt":1.0,"modelSelection":{"instanceId":"codex","model":"gpt-5"}}""",
        )
        val room = CompanionJson.decodeFromString(
            Room.serializer(),
            """{"id":"room-1","threadId":"rt","name":"Team","memberIds":["bot-2"],
               "defaultResponder":{"kind":"mentions"},"bulletin":"","unread":false,"createdAt":1}""",
        )
        val messages = messages()
        assertEquals("bot-1", Chat.BotChat(bot).connectorOwner(messages.getValue("conn-required")))
        assertEquals("bot-2", Chat.RoomChat(room).connectorOwner(messages.getValue("conn-authorizing")))
        assertNull(Chat.RoomChat(room).connectorOwner(messages.getValue("conn-required")), "never guess a member")
    }

    @Test
    fun rosterSaysWhatTheBotIsWaitingOnAndDismissedCardsLeaveTheTranscript() {
        val messages = messages()
        assertEquals("Connect GitHub to continue.", previewText(messages.getValue("conn-required")))
        assertEquals("Linear", previewText(messages.getValue("conn-connected")))
        assertEquals("Jira", previewText(messages.getValue("conn-newer")))

        val ordered = decodeFixture<ThreadPage>("connector-cards").messages
        val rows = transcriptRows(ordered, ActivityDetail.FULL).map { it.id }
        assertFalse("conn-dismissed" in rows)
        assertEquals(5, rows.size)
    }

    // Requests

    @Test
    fun buildsOnlyRoutesTheComputerWouldMatch() {
        assertEquals(
            "/api/bots/bot_1-a/connector-cards/m-2/authorize",
            ConnectorRequestAction.AUTHORIZE.path("bot_1-a", "m-2"),
        )
        assertEquals("GET", ConnectorRequestAction.STATUS.method)
        assertEquals("POST", ConnectorRequestAction.DISMISS.method)
        for (bad in listOf("", "a/b", "../x", "café", "a b", "a?b", "a".repeat(201))) {
            assertNull(ConnectorRequestAction.RESUME.path(bad, "m"), bad)
            assertNull(ConnectorRequestAction.RESUME.path("b", bad), bad)
        }
    }

    @Test
    fun authorizePostsTheThreadAndReturnsTheHttpsLink() = runBlocking {
        server.enqueue(json("""{"url":"https://connect.composio.dev/link/abc"}"""))

        val url = client.authorizeConnectorRequest("bot-1", "msg-1", "thread-1")

        assertEquals("https://connect.composio.dev/link/abc", url.toString())
        val request = server.takeRequest()
        assertEquals("POST /api/bots/bot-1/connector-cards/msg-1/authorize", "${request.method} ${request.path}")
        assertEquals("Bearer paired-token", request.getHeader("Authorization"))
        val body = CompanionJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(mapOf("threadId" to "thread-1"), body.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun refusesALinkThatIsNotHttps() = runBlocking {
        for (link in listOf("http://connect.composio.dev/x", "javascript:alert(1)", "https:///nohost", "not a url")) {
            server.enqueue(json("""{"url":"$link"}"""))
            assertFailsWith<APIError.BadUrl>(link) { client.authorizeConnectorRequest("b", "m", "t") }
            server.takeRequest()
        }
    }

    @Test
    fun refusesAnUnsafeIdBeforeSendingAnything() = runBlocking {
        assertFailsWith<APIError.BadUrl> { client.connectorRequestStatus("../bots", "m", "t") }
        assertFailsWith<APIError.BadUrl> { client.dismissConnectorRequest("b", "m", "") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun statusIsAGetWithTheThreadInTheQuery() = runBlocking {
        server.enqueue(json("""{"connected":true,"pending":false,"status":"ACTIVE"}"""))

        val status = client.connectorRequestStatus("bot-1", "msg-1", "thread 1")

        assertTrue(status.connected)
        assertEquals("ACTIVE", status.status)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/bots/bot-1/connector-cards/msg-1/status", request.requestUrl?.encodedPath)
        assertEquals("thread 1", request.requestUrl?.queryParameter("threadId"))
    }

    @Test
    fun resumeAndDismissPostTheThreadAndSurfaceTheComputersRefusal() = runBlocking {
        server.enqueue(json("""{"dismissed":true}"""))
        client.dismissConnectorRequest("bot-1", "msg-1", "thread-1")
        assertEquals("/api/bots/bot-1/connector-cards/msg-1/dismiss", server.takeRequest().path)

        server.enqueue(json("""{"error":"finish connecting every requested app first"}""", 409))
        val refused = assertFailsWith<APIError.Status> { client.resumeConnectorRequest("bot-1", "msg-1", "thread-1") }
        assertEquals("finish connecting every requested app first", refused.message)
        val request = server.takeRequest()
        assertEquals("/api/bots/bot-1/connector-cards/msg-1/resume", request.path)
        val body = CompanionJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(mapOf("threadId" to "thread-1"), body.mapValues { it.value.jsonPrimitive.content })
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
