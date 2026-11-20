package com.cloudshelf.reader.domain.model

import kotlinx.serialization.Serializable

/**
 * Canonical annotation schema normalized to PDF page space [0..1]
 * (origin top-left) so annotations remain accurate across zoom, rotation, and density.
 */
@Serializable
data class HighlightPayload(
    val pageWidthNorm: Float = 1f,
    val pageHeightNorm: Float = 1f,
    val rects: List<NormRect>,
    val colorArgb: Long,
    val selectedText: String? = null
)

@Serializable
data class DrawingPayload(
    val strokes: List<Stroke>,
    val colorArgb: Long,
    val strokeWidth: Float,
    val opacity: Float = 1f
)

@Serializable
data class ShapePayload(
    val shapeType: String, // RECT, ELLIPSE, LINE, ARROW, HIGHLIGHT_AREA
    val start: NormPoint,
    val end: NormPoint,
    val strokeColorArgb: Long,
    val fillColorArgb: Long? = null,
    val strokeWidth: Float,
    val opacity: Float = 1f
)

@Serializable
data class TextBoxPayload(
    val topLeft: NormPoint,
    val widthNorm: Float,
    val heightNorm: Float,
    val text: String,
    val fontSizeSp: Float,
    val textColorArgb: Long,
    val backgroundColorArgb: Long? = null,
    val alignment: String = "LEFT"
)

@Serializable
data class CommentPayload(
    val anchor: NormPoint,
    val text: String
)

@Serializable
data class StampPayload(
    val anchor: NormPoint,
    val label: String,
    val colorArgb: Long
)

@Serializable
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

@Serializable
data class NormPoint(val x: Float, val y: Float)

@Serializable
data class Stroke(
    val points: List<NormPoint>,
    val width: Float,
    val colorArgb: Long,
    val opacity: Float = 1f
)

typealias NormalizedPoint = NormPoint

enum class AnnotationTool {
    NONE,
    SELECT,
    PEN,
    PENCIL,
    HIGHLIGHTER_STROKE,
    HIGHLIGHT_AREA,
    UNDERLINE,
    STRIKEOUT,
    SQUIGGLY,
    RECTANGLE,
    ELLIPSE,
    LINE,
    ARROW,
    TEXT_BOX,
    POPUP_NOTE,
    STAMP,
    ERASER
}

@Serializable
sealed class AnnotationPayload {
    @Serializable
    data class FreehandStroke(
        val points: List<NormalizedPoint>,
        val color: Long,
        val strokeWidth: Float,
        val alpha: Float = 1f,
        val tool: AnnotationTool = AnnotationTool.PEN
    ) : AnnotationPayload()

    @Serializable
    data class ShapeItem(
        val shapeType: AnnotationTool,
        val start: NormalizedPoint,
        val end: NormalizedPoint,
        val strokeColor: Long,
        val fillColor: Long? = null,
        val strokeWidth: Float,
        val alpha: Float = 1f
    ) : AnnotationPayload()

    @Serializable
    data class TextItem(
        val topLeft: NormalizedPoint,
        val widthNorm: Float = 0.28f,
        val heightNorm: Float = 0.08f,
        val text: String,
        val color: Long,
        val fontSizeSp: Float = 14f,
        val backgroundColor: Long? = 0xEEFFF8DC
    ) : AnnotationPayload()

    @Serializable
    data class PopupNoteItem(
        val anchor: NormalizedPoint,
        val text: String,
        val color: Long = 0xFFF59E0B
    ) : AnnotationPayload()

    @Serializable
    data class StampItem(
        val anchor: NormalizedPoint,
        val label: String,
        val color: Long = 0xFFDC2626
    ) : AnnotationPayload()
}

@Serializable
data class RenderedAnnotation(
    val id: String,
    val bookId: String,
    val pageNumber: Int,
    val payload: AnnotationPayload,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class AnnotationExportBundle(
    val schemaVersion: Int = 1,
    val app: String = "CloudShelf",
    val bookId: String,
    val annotations: List<RenderedAnnotation>
)
