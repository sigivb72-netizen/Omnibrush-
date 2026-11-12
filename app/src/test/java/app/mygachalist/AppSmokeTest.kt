package app.mygachalist

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import app.mygachalist.data.GamesParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app (real seed data, real Room database) through every screen on the JVM.
 * Its job is to catch crashes; it also writes screenshots to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class AppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        val dir = File(System.getProperty("screenshotDir") ?: return).apply { mkdirs() }
        runCatching {
            rule.waitForIdle()
            val view = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name failed: $it") }
    }

    private fun waitForText(text: String) = rule.waitUntil(15_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun walkThroughEveryScreen() {
        // Pick a real game from the repo's database so the test follows the data instead of hard-coding it.
        val seed = listOf(File("../data/games.json"), File("data/games.json")).first { it.exists() }
        val target = GamesParser.parse(seed.readText()).games.filter { !it.adult }.maxBy { it.popularity }.title
        val isResult = hasText(target) and !hasSetTextAction()

        // Home, from the bundled seed
        waitForText("Releasing soon")
        shot("01-home")

        // Seasonal: all three modes and the period arrows
        rule.onNodeWithText("Seasonal").performClick()
        waitForText("Season")
        shot("02-seasonal")
        rule.onNodeWithText("Month").performClick()
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithContentDescription("Previous").performClick()
        rule.onNodeWithText("TBA").performClick()
        shot("03-seasonal-tba")

        // Search and open a game
        rule.onNodeWithText("Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput(target)
        rule.waitUntil(15_000) { rule.onAllNodes(isResult).fetchSemanticsNodes().isNotEmpty() }
        shot("04-search")
        rule.onAllNodes(isResult).onFirst().performClick()
        waitForText("Release dates")
        shot("05-detail")

        // Add to list as Plan to Play
        rule.onNodeWithText("Add to list").performClick()
        waitForText("Money spent")
        rule.onNodeWithText("Plan to Play").performClick()
        shot("06-entry-edit")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        waitForText("Information")
        rule.onNodeWithContentDescription("Back").performClick()

        // My List, Profile, Settings
        rule.onNodeWithText("My List").performClick()
        waitForText("Playing (0)")
        rule.onNodeWithText("Plan to Play (1)").performScrollTo().performClick()
        waitForText("1 entry")
        shot("07-mylist")
        rule.onNodeWithText("Profile").performClick()
        waitForText("Genre breakdown")
        shot("08-profile")
        rule.onNodeWithContentDescription("Settings").performClick()
        waitForText("Default region")
        shot("09-settings")
        rule.onNodeWithText("Theme").performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Home").performClick()
        waitForText("Releasing soon")
        shot("10-home-dark")
    }
}
