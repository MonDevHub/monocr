package dev.janakhpon.monocr.ui

import dev.janakhpon.monocr.engine.EmptyPdfException
import dev.janakhpon.monocr.engine.LineInferenceException
import dev.janakhpon.monocr.engine.UnreadableImageException
import java.io.IOException

/**
 * Why a scan failed, as a type the screen can turn into one localised message.
 *
 * This replaced matching keywords in the English exception text, which sent a 60 MB
 * file to "try a clearer image" and could never show the CLI guide.
 */
sealed interface OcrFailure {
    /** Over the import cap. monocr-cli takes much larger files. */
    data class FileTooLarge(val limitMb: Int) : OcrFailure
    /** The file could not be opened at all: moved, deleted, or access withdrawn. */
    data object UnreadableFile : OcrFailure
    /** The file opened but is not an image this device can decode. */
    data object UnreadableImage : OcrFailure
    data object PdfNoPages : OcrFailure
    data object OutOfMemory : OcrFailure
    /** Every line failed in the model runtime, so nothing was read. */
    data object ModelRuntime : OcrFailure
    /** Anything else. [detail] is for logs, not for the screen. */
    data class Unknown(val detail: String?) : OcrFailure

    companion object {
        fun of(error: Throwable): OcrFailure = when (error) {
            is UnreadableImageException -> UnreadableImage
            is EmptyPdfException -> PdfNoPages
            is LineInferenceException -> ModelRuntime
            is OutOfMemoryError -> OutOfMemory
            is IOException, is SecurityException -> UnreadableFile
            else -> Unknown(error.message)
        }
    }
}
