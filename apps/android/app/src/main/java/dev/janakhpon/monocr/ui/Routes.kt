package dev.janakhpon.monocr.ui

import androidx.navigation.NavController

/**
 * The route without its argument template.
 *
 * `destination.route` returns the pattern a destination was registered with, such as
 * `feedback?text={text}&imageUri={imageUri}`, so comparing it with "feedback" was never
 * true: the drawer never highlighted Feedback or Contribute, and its "already here"
 * guard never held.
 */
fun baseRoute(route: String?): String? = route?.substringBefore('?')

/**
 * Leaves the intro for Home with nothing behind it. The intro can be opened from the
 * drawer on top of Home, and popping only "intro" left [home, home].
 */
fun NavController.finishIntro() {
    navigate("home") {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
