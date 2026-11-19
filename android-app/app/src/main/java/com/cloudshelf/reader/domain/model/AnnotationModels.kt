package com.cloudshelf.reader.domain.model

import kotlinx.serialization.Serializable

enum class AnnotationTool {
    NONE,
    HIGHLIGHT_AREA,
    PEN,
    PENCIL,
    HIGHLIGHTER_STROKE,
    ERASER,
    RECTANGLE,
    ELLIPSE,
    LINE,
    ARROW,
    TEXT_BOX,
    COMMENT_PIN
}

@Serializable
data class NormalizedPoint(val x: Float, val y: Float)

@Serializable
sealed class AnnotationPayload {
    @Serializable
    data class FreehandStroke(
        val points: List<NormalizedPoint>,
        val colorArgb: Long,
        val strokeWidthRatio: Float,
        val alpha: Float
    ) : AnnotationPayload()

    @Serializable
    data class ShapeItem(
        val shapeType: String, // RECTANGLE, ELLIPSE, LINE, ARROW, HIGHLIGHT_AREA
        val start: NormalizedPoint,
        val end: NormalizedPoint,
        val strokeColorArgb: Long,
        val fillColorArgb: Long?,
        val strokeWidthRatio: Float,
        val alpha: Float
    ) : AnnotationPayload()

    @Serializable
    data class TextBoxItem(
        val topLeft: NormalizedPoint,
        val bottomRight: NormalizedPoint,
        val text: String,
        val textColorArgb: Long,
        val backgroundColorArgb: Long,
        val fontSizeSp: Float
    ) : AnnotationPayload()

    @Serializable
    data class CommentPinItem(
        val anchor: NormalizedPoint,
        val commentText: String,
        val colorArgb: Long
    ) : AnnotationPayload()
}
