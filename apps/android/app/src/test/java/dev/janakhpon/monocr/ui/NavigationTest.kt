package dev.janakhpon.monocr.ui

import android.net.Uri
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.ui.components.AppDrawer
import dev.janakhpon.monocr.ui.screens.FeedbackContent
import dev.janakhpon.monocr.ui.screens.SelectedFilePreview
import dev.janakhpon.monocr.ui.theme.MonOCRTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a registered pattern reduces to its base route`() {
        assertEquals("feedback", baseRoute("feedback?text={text}&imageUri={imageUri}"))
        assertEquals("contribute", baseRoute("contribute?text={text}"))
        assertEquals("home", baseRoute("home"))
        assertEquals(null, baseRoute(null))
    }

    @Test fun `the drawer highlights Feedback on its registered pattern`() {
        compose.setContent {
            MonOCRTheme(darkTheme = false) {
                AppDrawer(
                    drawerState = rememberDrawerState(DrawerValue.Open),
                    scope = rememberCoroutineScope(),
                    onNavigate = {},
                    currentRoute = "feedback?text={text}&imageUri={imageUri}"
                )
            }
        }
        compose.onNodeWithText(str(R.string.nav_feedback)).assertIsSelected()
        compose.onNodeWithText(str(R.string.nav_home)).assertIsNotSelected()
    }

    @Test fun `finishing the intro opened over Home leaves one Home`() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") {}
                composable("intro") {}
            }
        }
        compose.runOnIdle { nav.navigate("intro") }
        compose.runOnIdle { nav.finishIntro() }
        compose.runOnIdle {
            val routes = nav.currentBackStack.value.mapNotNull { it.destination.route }
            assertEquals(listOf("home"), routes)
        }
    }

    @Test fun `feedback cancel leaves the screen instead of opening the drawer`() {
        var cancelled = 0
        var menu = 0
        compose.setContent {
            MonOCRTheme(darkTheme = false) {
                FeedbackContent(
                    feedbackHistory = emptyList(), originalText = "", sourceUriDefault = null,
                    onMenuClick = { menu++ }, onCancel = { cancelled++ },
                    onSubmit = { _, _, _, _ -> }, onDelete = {}, onClearAll = {}
                )
            }
        }
        compose.onNodeWithText(str(R.string.feedback_cancel)).performScrollTo().performClick()
        assertEquals(1, cancelled)
        assertEquals(0, menu)
    }

    @Test fun `a text file is previewed by name, not as a broken PDF`() {
        compose.setContent {
            MonOCRTheme(darkTheme = false) {
                SelectedFilePreview(Uri.parse("content://test/notes.txt"), "text/plain", "notes.txt")
            }
        }
        compose.onNodeWithText("notes.txt").assertExists()
        compose.onAllNodesWithText(str(R.string.pdf_preview_unavailable)).assertCountEquals(0)
    }
}
