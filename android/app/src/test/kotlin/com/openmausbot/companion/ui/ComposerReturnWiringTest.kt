package com.openmausbot.companion.ui

import android.view.InputDevice
import android.view.KeyCharacterMap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.openmausbot.companion.core.Chat
import com.openmausbot.companion.core.CompanionJson
import com.openmausbot.companion.core.Connection
import com.openmausbot.companion.core.Fleet
import com.openmausbot.companion.core.Frame
import com.openmausbot.companion.core.StreamFrame
import com.openmausbot.companion.core.target
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Return from a hardware keyboard, through the real composer and Session: the
 * key events are the ones a Bluetooth or tablet keyboard delivers, and whether
 * anything was sent is read off the loopback server rather than a callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class ComposerReturnWiringTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        // The header mascot normally requests frames continuously; reduce
        // motion so the rule can reach idle between key presses.
        effectContext = object : MotionDurationScale { override val scaleFactor = 0f },
    )

    private lateinit var server: MockWebServer
    private lateinit var scene: WiringScene
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private val fixture = bot().copy(messages = emptyList())
    private val sendPath = "/api/bots/${fixture.id}/messages"

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when {
                    request.method == "POST" && request.path == sendPath ->
                        json("""{"threadId":"${fixture.threadId}"}""").setResponseCode(202)
                    request.path == "/api/instances" -> json("""{"instances":[]}""")
                    else -> json("""{"messages":[],"hasMore":false}""")
                }
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        if (::scene.isInitialized) scene.session.disconnect()
        server.shutdown()
    }

    @Test
    fun `hardware Shift+Return breaks the line and a bare Return sends it`() {
        mount()
        val field = compose.onNode(hasSetTextAction())

        field.performTextInput("one")
        field.press(shift = true, deviceId = HARDWARE_KEYBOARD)
        field.performTextInput("two")

        field.assertTextEquals("one\ntwo")
        assertTrue(sends().isEmpty(), "Shift+Return must not send")

        field.press(shift = false, deviceId = HARDWARE_KEYBOARD)

        compose.waitUntil(5_000) { sends().isNotEmpty() }
        val body = CompanionJson.decodeFromString<JsonObject>(sends().single().body.readUtf8())
        assertEquals("one\ntwo", body["text"]?.jsonPrimitive?.content)
        field.assertTextEquals("")
    }

    @Test
    fun `Shift+Return types the break over the selection and leaves the caret after it`() {
        mount()
        val field = compose.onNode(hasSetTextAction())

        field.performTextInput("one-two")
        field.performTextInputSelection(TextRange(3, 4))
        field.press(shift = true, deviceId = HARDWARE_KEYBOARD)
        field.performTextInput("&")

        field.assertTextEquals("one\n&two")
        assertTrue(sends().isEmpty(), "Shift+Return must not send")
    }

    @Test
    fun `the software keyboard's Return still breaks the line without sending`() {
        mount()
        val field = compose.onNode(hasSetTextAction())

        field.performTextInput("one")
        field.press(shift = false, deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD)
        field.performTextInput("two")

        field.assertTextEquals("one\ntwo")
        assertTrue(sends().isEmpty(), "the software keyboard's Return must not send")
    }

    private fun sends() = requests.filter { it.method == "POST" && it.path == sendPath }

    /** Down then up, as a keyboard delivers a press. */
    private fun SemanticsNodeInteraction.press(shift: Boolean, deviceId: Int) {
        val meta = if (shift) android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON else 0
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            performKeyPress(
                KeyEvent(
                    android.view.KeyEvent(
                        0L, 0L, action, android.view.KeyEvent.KEYCODE_ENTER, 0, meta,
                        deviceId, 0, 0, InputDevice.SOURCE_KEYBOARD,
                    ),
                ),
            )
        }
        compose.waitForIdle()
    }

    private fun mount() {
        scene = WiringScene(
            connection = Connection(id = "return-fixture", name = "Offline fixture", host = "127.0.0.1", port = server.port),
            fleet = Fleet(listOf(fixture), emptyList()),
            events = {
                flow {
                    emit(StreamFrame(Frame.Hello(cursor = "fixture:1", resumed = false), seq = 1))
                    awaitCancellation()
                }
            },
        )
        compose.setContent {
            CompositionLocalProvider(LocalCompanion provides scene.environment) {
                CompanionTheme(darkTheme = false) {
                    val state by scene.session.state.collectAsState()
                    if (state.bot(fixture.id) != null) ChatScreen(
                        Destination.Chat(Chat.BotChat(fixture).target),
                        onResolved = {}, onBack = {}, onOpenComputer = {}, onOpenOverview = {},
                    )
                }
            }
        }
        compose.runOnIdle { scene.session.connect() }
        compose.waitUntil(5_000) { scene.session.state.value.bot(fixture.id) != null }
        compose.waitForIdle()
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        /** Any real input device; only the on-screen keyboard is [KeyCharacterMap.VIRTUAL_KEYBOARD]. */
        const val HARDWARE_KEYBOARD = 1
    }
}
