package ch.snepilatch.app.data

import android.content.Context
import androidx.annotation.StringRes

/**
 * User-facing text a ViewModel wants shown, as a string resource plus its format args. ViewModels
 * have no Context, and resolving in the UI is what makes the text follow the picked app language.
 * [raw] carries text that has no resource to begin with (an exception message from the server).
 */
data class UiMessage(
    @param:StringRes val id: Int = 0,
    val args: List<Any> = emptyList(),
    val raw: String? = null
) {
    fun resolve(context: Context): String = raw ?: context.getString(id, *args.toTypedArray())
}
