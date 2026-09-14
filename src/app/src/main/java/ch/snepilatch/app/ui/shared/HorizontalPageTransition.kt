package ch.snepilatch.app.ui.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier

@Composable
fun <T : Any> HorizontalPageTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    rules: HorizontalPageTransitionRules<T> = HorizontalPageTransitionRules(),
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            when (resolveHorizontalPageDirection(initialState, targetState, rules)) {
                HorizontalPageTransitionDirection.Left ->
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                HorizontalPageTransitionDirection.Right ->
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                null -> EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "horizontalPageTransition",
        content = { page ->
            if (shouldRetainHorizontalPageState(page, rules)) {
                saveableStateHolder.SaveableStateProvider(rules.stateKey(page)) { content(page) }
            } else {
                content(page)
            }
        },
    )
}

/**
 * Configures slide direction and saveable-state retention for [HorizontalPageTransition].
 *
 * Add a page to [pages] when it needs a fixed direction. If both endpoints have rules, the
 * initial page wins and its direction is reversed for the transition out. Add a parent to
 * [subtrees] and provide [parentOf] to apply one rule to that page and its descendants. Sibling
 * pages have no inferred direction, so add an exact [pages] rule when they should slide.
 *
 * If [T] represents a parameterized route, include its parameters in the value. Set [stateKey]
 * to return a unique key for each retained route instance so their `rememberSaveable` state stays
 * separate.
 */
data class HorizontalPageTransitionRules<T : Any>(
    val pages: Map<T, HorizontalPageTransitionDirection> = emptyMap(),
    val subtrees: Map<T, HorizontalPageTransitionDirection> = emptyMap(),
    val parentOf: (T) -> T? = { null },
    val stateKey: (T) -> Any = { it },
)

internal fun <T : Any> resolveHorizontalPageDirection(
    initialState: T,
    targetState: T,
    rules: HorizontalPageTransitionRules<T>,
): HorizontalPageTransitionDirection? {
    rules.pages[initialState]?.let { return it.opposite() }
    rules.pages[targetState]?.let { return it }

    val targetAncestors = targetState.ancestors(rules.parentOf).toList()
    if (initialState in targetAncestors) {
        return targetAncestors.firstNotNullOfOrNull(rules.subtrees::get)
    }

    val initialAncestors = initialState.ancestors(rules.parentOf).toList()
    if (targetState in initialAncestors) {
        return initialAncestors
            .firstNotNullOfOrNull(rules.subtrees::get)
            ?.opposite()
    }
    return null
}

internal fun <T : Any> shouldRetainHorizontalPageState(
    state: T,
    rules: HorizontalPageTransitionRules<T>,
): Boolean = state in rules.pages ||
    state in rules.subtrees ||
    state.ancestors(rules.parentOf).any { it in rules.subtrees }

private fun <T : Any> T.ancestors(parentOf: (T) -> T?): Sequence<T> =
    sequence {
        val visited = mutableSetOf(this@ancestors)
        var current = parentOf(this@ancestors)
        while (current != null && visited.add(current)) {
            yield(current)
            current = parentOf(current)
        }
    }

enum class HorizontalPageTransitionDirection {
    Left,
    Right;

    fun opposite() = when (this) {
        Left -> Right
        Right -> Left
    }
}
