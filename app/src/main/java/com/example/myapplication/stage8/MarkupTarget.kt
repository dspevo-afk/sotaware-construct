package com.example.myapplication.stage8

/**
 * The surface a markup edit belongs to. The viewer names the surface it is
 * showing and the reducer routes the edit to today's storage for it, so the
 * renderer never chooses between page, sub-drawing and photo commands itself.
 *
 * [Photo] names a pin by id only: the reducer finds whether that pin lives on
 * the page or inside one of the page's sub-drawings, so an edit on a photo of
 * a sub-drawing pin can never be sent to the page by mistake.
 */
sealed interface MarkupTarget {
    val page: Int

    /** The PDF page itself. */
    data class Sheet(override val page: Int) : MarkupTarget

    /** The inside of one sub-drawing on [page]. */
    data class SubDrawing(override val page: Int, val subId: String) : MarkupTarget

    /** One photo ([fileName]) of the pin [pinId], wherever on [page] that pin is stored. */
    data class Photo(override val page: Int, val pinId: String, val fileName: String) : MarkupTarget
}
