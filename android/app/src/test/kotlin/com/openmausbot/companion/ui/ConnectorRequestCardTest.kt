package com.openmausbot.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openmausbot.companion.core.ConnectorRequest
import com.openmausbot.companion.core.ConnectorRequestPresentation
import com.openmausbot.companion.core.ConnectorRequestPresentation.PrimaryAction
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What each state of the in-chat connection card draws, offline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectorRequestCardTest {
    @get:Rule val compose = createComposeRule()

    private val github = ConnectorRequest(
        slug = "github",
        label = "GitHub",
        description = "Connect GitHub so the bot can open the pull request.",
        status = ConnectorRequest.Status.REQUIRED,
        resumeKey = "resume-123",
    )

    private fun show(
        request: ConnectorRequest,
        failure: ConnectorCardFailure? = null,
        onPrimary: (PrimaryAction) -> Unit = {},
        onNotNow: () -> Unit = {},
    ) = compose.setContent {
        CompanionTheme(darkTheme = false) {
            ConnectorRequestCard(
                request = request,
                presentation = requireNotNull(ConnectorRequestPresentation.of(request)),
                requester = "Pepper",
                tint = Color.Blue,
                fallbackText = "Connect GitHub to continue.",
                busy = false,
                failure = failure,
                onPrimary = onPrimary,
                onNotNow = onNotNow,
            )
        }
    }

    @Test
    fun requiredOffersConnectSecurelyAndNotNow() {
        val tapped = mutableListOf<String>()
        show(github, onPrimary = { tapped += it.name }, onNotNow = { tapped += "not now" })
        compose.onNodeWithText("GitHub").assertIsDisplayed()
        compose.onNodeWithText("Connect GitHub so the bot can open the pull request.").assertIsDisplayed()
        compose.onNodeWithText("Sign in or enter the app key on the secure connection page — never in chat.").assertIsDisplayed()
        compose.onNodeWithText("Requested by Pepper").assertIsDisplayed()
        compose.onNodeWithText("Connect securely").performClick()
        compose.onNodeWithContentDescription("Not now").performClick()
        assertEquals(listOf("CONNECT", "not now"), tapped)
    }

    @Test
    fun authorizingWaitsForSignInWithOpenAgain() {
        show(github.copy(status = ConnectorRequest.Status.AUTHORIZING))
        compose.onNodeWithText("Waiting for sign-in…").assertIsDisplayed()
        compose.onNodeWithText("Open again").assertIsDisplayed()
    }

    @Test
    fun failedSaysWhyAndOffersTryAgain() {
        show(github.copy(status = ConnectorRequest.Status.FAILED, error = "Connection EXPIRED"))
        compose.onNodeWithText("Connection EXPIRED").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun aLocalFailureReadsInPlaceOfTheComputersError() {
        show(github, failure = ConnectorCardFailure.InvalidLink)
        compose.onNodeWithText("The connection page link wasn't valid. Try again.").assertIsDisplayed()
    }

    @Test
    fun connectedOffersContinueTaskUntilTheBotResumes() {
        val tapped = mutableListOf<PrimaryAction>()
        show(github.copy(status = ConnectorRequest.Status.CONNECTED), onPrimary = { tapped += it })
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Connected securely. Continue the paused task when you're ready.").assertIsDisplayed()
        compose.onNodeWithText("Ready to use").assertIsDisplayed()
        compose.onNodeWithText("Continue task").performClick()
        assertEquals(listOf(PrimaryAction.CONTINUE_TASK), tapped)
        assertEquals(0, compose.onAllNodesWithTag("connector-card.not-now").fetchSemanticsNodes().size)
    }

    @Test
    fun resumedShowsContinuingWithNothingToTap() {
        show(github.copy(status = ConnectorRequest.Status.CONNECTED, resumed = true))
        compose.onNodeWithText("Connected securely. Your bot is continuing the task.").assertIsDisplayed()
        compose.onNodeWithText("Continuing").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("connector-card.primary").fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun simplifiedChineseCard() {
        show(github)
        compose.onNodeWithText("安全连接").assertIsDisplayed()
        compose.onNodeWithText("由 Pepper 请求").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun traditionalChineseCard() {
        show(github.copy(status = ConnectorRequest.Status.AUTHORIZING))
        compose.onNodeWithText("再次開啟").assertIsDisplayed()
        compose.onNodeWithText("正在等待登入…").assertIsDisplayed()
    }
}
