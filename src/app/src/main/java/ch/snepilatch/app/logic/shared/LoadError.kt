package ch.snepilatch.app.logic.shared

import kotify.api.NotFoundException
import kotify.http.HttpException

/** Why a page has nothing to show: worth a retry, or gone for good, as the web player tells them apart. */
enum class LoadError { GENERIC, NOT_FOUND }

fun loadErrorOf(e: Exception): LoadError =
    if (e is NotFoundException || (e is HttpException && e.code == 404)) LoadError.NOT_FOUND else LoadError.GENERIC
