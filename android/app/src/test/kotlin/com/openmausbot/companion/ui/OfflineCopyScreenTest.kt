package com.openmausbot.companion.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openmausbot.companion.core.Bot
import com.openmausbot.companion.core.ChatTarget
import com.openmausbot.companion.core.Connection
import com.openmausbot.companion.core.Message
import com.openmausbot.companion.core.ModelSelection
import com.openmausbot.companion.core.OptionCard
import com.openmausbot.companion.core.StateSnapshot
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The saved copy on screen (MOCA-296): Home and the chat say "Not connected ·
 * last updated …", the composer is replaced by "Reconnect to send", an ask
 * shows "Reconnect to answer" instead of its buttons, there is no call button,
 * and tapping the banner tries the computer again. The session is real; the
 * computer never answers, so the copy stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class OfflineCopyScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        effectContext = object : MotionDurationScale { override val scaleFactor = 0f },
    )

    private lateinit var scene: WiringScene

    private val scout = Bot(
        id = "bot-1",
        threadId = "thread-1",
        name = "Scout",
        title = "Helper",
        description = "",
        notifications = true,
        color = "green",
        unread = false,
        modelSelection = ModelSelection("claude", "opus"),
        createdAt = 1.0,
    )

    private val ask = Message(
        id = "ask",
        role = Message.Role.BOT,
        kind = Message.Kind.OPTIONS,
        at = 2.0,
        parentId = "hello",
        card = OptionCard(title = "Run the deploy?", subtitle = "npm run deploy", options = listOf("Allow", "Deny"), requestId = "req-1"),
    )

    private val saved = StateSnapshot(
        schemaVersion = StateSnapshot.SCHEMA_VERSION,
        connectionId = "offline-fixture",
        savedAt = 1_700_000_000_000,
        bots = listOf(StateSnapshot.CachedBot(scout)),
        threads = mapOf(
            "thread-1" to StateSnapshot.CachedThread(
                messages = listOf(
                    StateSnapshot.CachedMessage(Message("hello", Message.Role.USER, Message.Kind.TEXT, 1.0, text = "Ship it when green")),
                    StateSnapshot.CachedMessage(ask),
                ),
                hasMore = false,
            ),
        ),
    )

    @After
    fun tearDown() {
        if (::scene.isInitialized) scene.session.disconnect()
    }

    @Test
    fun `a chat on the saved copy reads but offers nothing to send or answer`() {
        mount {
            ChatScreen(
                destination = Destination.Chat(ChatTarget.Bot(scout.id, scout.threadId)),
                onResolved = {},
                onBack = {},
                onOpenComputer = {},
                onOpenOverview = {},
            )
        }

        compose.onNodeWithText("Ship it when green").assertIsDisplayed()
        compose.onNodeWithText("Not connected · last updated", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Reconnect to send").assertIsDisplayed()
        compose.onNodeWithText("Reconnect to answer").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Allow").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Call Scout").assertDoesNotExist()
        assertTrue(scene.session.state.value.isCached)
    }

    @Test
    fun `Home's banner names the copy and tapping it tries the computer again`() {
        mount { Column { StatusBanner() } }
        val before = scene.streamStarts.get()

        compose.onNodeWithText("Not connected · last updated", substring = true).performClick()
        compose.waitUntil(5_000) { scene.streamStarts.get() > before }
    }

    @Test
    fun `last updated is a time today and a date with a time before`() {
        val zone = ZoneId.of("UTC")
        val noon = 1_700_049_600_000L // 2023-11-15 12:00 UTC
        val today = OfflineCopyRules.lastUpdated(noon - 3_600_000, noon, zone, Locale.US)
        val earlier = OfflineCopyRules.lastUpdated(noon - 2 * 86_400_000L, noon, zone, Locale.US)

        assertEquals("11:00 AM", today.replace(' ', ' '))
        assertTrue("Nov 13, 2023" in earlier && "12:00" in earlier, earlier)
    }

    private fun mount(content: @Composable () -> Unit) {
        scene = WiringScene(
            // Nothing listens here; the stream never says hello.
            connection = Connection(id = "offline-fixture", name = "Fixture", host = "127.0.0.1", port = 9),
            snapshot = saved,
        )
        compose.setContent {
            CompositionLocalProvider(LocalCompanion provides scene.environment) {
                CompanionTheme(darkTheme = false) {
                    val state by scene.session.state.collectAsState()
                    if (state.bot(scout.id) != null) content()
                }
            }
        }
        compose.runOnIdle { scene.session.connect() }
        compose.waitUntil(5_000) { scene.session.state.value.isCached }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Not connected · last updated", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
