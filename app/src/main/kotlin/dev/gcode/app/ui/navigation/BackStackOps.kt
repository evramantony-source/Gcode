package dev.gcode.app.ui.navigation

import androidx.navigation3.runtime.NavKey

/**
 * Back-stack rules for the app shell. Kept as plain list operations so they are unit-testable.
 *
 * The stack always starts with a top-level route. Selecting a top-level destination resets the stack
 * to it. Back pops; at the root of any tab other than Chat, Back returns to Chat before leaving the app.
 */
object BackStackOps {
    fun currentTopLevel(stack: List<NavKey>): TopLevelDestination =
        TopLevelDestination.of(stack.firstOrNull()) ?: TopLevelDestination.START

    fun selectTopLevel(stack: MutableList<NavKey>, destination: TopLevelDestination) {
        if (stack.size == 1 && stack.first() == destination.route) return
        stack.clear()
        stack.add(destination.route)
    }

    fun navigate(stack: MutableList<NavKey>, route: NavKey) {
        val top = TopLevelDestination.of(route)
        if (top != null) {
            selectTopLevel(stack, top)
        } else if (stack.lastOrNull() != route) {
            stack.add(route)
        }
    }

    /** @return false when the app should handle Back itself (i.e. leave). */
    fun back(stack: MutableList<NavKey>): Boolean = when {
        stack.size > 1 -> {
            stack.removeAt(stack.lastIndex)
            true
        }
        currentTopLevel(stack) != TopLevelDestination.START -> {
            selectTopLevel(stack, TopLevelDestination.START)
            true
        }
        else -> false
    }

    fun canGoBack(stack: List<NavKey>): Boolean =
        stack.size > 1 || currentTopLevel(stack) != TopLevelDestination.START
}
