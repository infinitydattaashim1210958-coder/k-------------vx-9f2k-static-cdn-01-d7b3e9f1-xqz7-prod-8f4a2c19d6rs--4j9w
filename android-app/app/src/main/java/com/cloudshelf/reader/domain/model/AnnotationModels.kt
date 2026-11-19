package com.cloudshelf.reader.domain.model

import kotlinx.serialization.Serializable

/**
 * All annotation coordinates are normalized to PDF page space [0..1]
 * (origin top-left) so they remain correct across zoom, rotation and density.
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
    val shapeType: String, // RECT, ELLIPSE, LINE, ARROW
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

/* Compatibility aliases used by AnnotationCanvasOverlay */
typealias NormalizedPoint = NormPoint

enum class AnnotationTool {
    NONE,
    PEN,
    PENCIL,
    HIGHLIGHTER_STROKE,
    HIGHLIGHT_AREA,
    RECTANGLE,
    ELLIPSE,
    LINE,
    ARROW,
    TEXT_BOX,
    ERASER
}

sealed class AnnotationPayload {
    data class FreehandStroke(
        val points: List<NormalizedPoint>,
        val color: Long,
        val strokeWidth: Float,
        val alpha: Float = 1f
    ) : AnnotationPayload()

    data class ShapeItem(
        val shapeType: AnnotationTool,
        val start: NormalizedPoint,
        val end: NormalizedPoint,
        val strokeColor: Long,
        val fillColor: Long? = null,
        val strokeWidth: Float,
        val alpha: Float = 1f
    ) : AnnotationPayload()

    data class TextItem(
        val topLeft: NormalizedPoint,
        val text: String,
        val color: Long
    ) : AnnotationPayload()
}
