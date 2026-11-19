package com.cloudshelf.reader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.modifier.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.cloudshelf.reader.domain.model.AnnotationPayload
import com.cloudshelf.reader.domain.model.AnnotationTool
import com.cloudshelf.reader.domain.model.NormalizedPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Overlay canvas for freehand and shape annotations.
 * Coordinates are normalized [0..1] relative to the page bounds.
 */
@Composable
fun AnnotationCanvasOverlay(
    activeTool: AnnotationTool,
    activeColor: Long = 0xFFFFEB3B,
    activeStrokeWidth: Float = 4f,
    activeAlpha: Float = 1f,
    committedAnnotations: List<AnnotationPayload> = emptyList(),
    onAnnotationCreated: (AnnotationPayload) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeStrokePoints = remember { mutableStateListOf<NormalizedPoint>() }
    var dragStart by remember { mutableStateOf<NormalizedPoint?>(null) }
    var dragCurrent by remember { mutableStateOf<NormalizedPoint?>(null) }

    val gestureModifier = if (activeTool == AnnotationTool.NONE) {
        Modifier
    } else {
        Modifier.pointerInput(activeTool, activeColor, activeStrokeWidth, activeAlpha) {
            detectDragGestures(
                onDragStart = { offset ->
                    val norm = NormalizedPoint(
                        x = (offset.x / size.width).coerceIn(0f, 1f),
                        y = (offset.y / size.height).coerceIn(0f, 1f)
                    )
                    dragStart = norm
                    dragCurrent = norm
                    activeStrokePoints.clear()
                    activeStrokePoints.add(norm)
                },
                onDrag = { change, _ ->
                    val norm = NormalizedPoint(
                        x = (change.position.x / size.width).coerceIn(0f, 1f),
                        y = (change.position.y / size.height).coerceIn(0f, 1f)
                    )
                    dragCurrent = norm
                    if (activeTool == AnnotationTool.PEN ||
                        activeTool == AnnotationTool.PENCIL ||
                        activeTool == AnnotationTool.HIGHLIGHTER_STROKE
                    ) {
                        activeStrokePoints.add(norm)
                    }
                },
                onDragEnd = {
                    when (activeTool) {
                        AnnotationTool.PEN,
                        AnnotationTool.PENCIL,
                        AnnotationTool.HIGHLIGHTER_STROKE -> {
                            if (activeStrokePoints.size >= 2) {
                                onAnnotationCreated(
                                    AnnotationPayload.FreehandStroke(
                                        points = activeStrokePoints.toList(),
                                        color = activeColor,
                                        strokeWidth = activeStrokeWidth,
                                        alpha = if (activeTool == AnnotationTool.HIGHLIGHTER_STROKE) 0.35f else activeAlpha
                                    )
                                )
                            }
                            activeStrokePoints.clear()
                        }
                        AnnotationTool.RECTANGLE,
                        AnnotationTool.ELLIPSE,
                        AnnotationTool.LINE,
                        AnnotationTool.ARROW,
                        AnnotationTool.HIGHLIGHT_AREA -> {
                            val start = dragStart
                            val end = dragCurrent
                            if (start != null && end != null) {
                                val isHighlight = activeTool == AnnotationTool.HIGHLIGHT_AREA
                                onAnnotationCreated(
                                    AnnotationPayload.ShapeItem(
                                        shapeType = activeTool,
                                        start = start,
                                        end = end,
                                        strokeColor = activeColor,
                                        fillColor = if (isHighlight) activeColor else null,
                                        strokeWidth = activeStrokeWidth,
                                        alpha = if (isHighlight) 0.3f else activeAlpha
                                    )
                                )
                            }
                        }
                        else -> Unit
                    }
                    dragStart = null
                    dragCurrent = null
                }
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize().then(gestureModifier)) {
        committedAnnotations.forEach { payload ->
            drawAnnotationPayload(payload)
        }
        // In-progress freehand
        if (activeStrokePoints.size >= 2 &&
            (activeTool == AnnotationTool.PEN ||
                activeTool == AnnotationTool.PENCIL ||
                activeTool == AnnotationTool.HIGHLIGHTER_STROKE)
        ) {
            drawAnnotationPayload(
                AnnotationPayload.FreehandStroke(
                    points = activeStrokePoints.toList(),
                    color = activeColor,
                    strokeWidth = activeStrokeWidth,
                    alpha = if (activeTool == AnnotationTool.HIGHLIGHTER_STROKE) 0.35f else activeAlpha
                )
            )
        }
        // In-progress shape
        val start = dragStart
        val end = dragCurrent
        if (start != null && end != null &&
            (activeTool == AnnotationTool.RECTANGLE ||
                activeTool == AnnotationTool.ELLIPSE ||
                activeTool == AnnotationTool.LINE ||
                activeTool == AnnotationTool.ARROW ||
                activeTool == AnnotationTool.HIGHLIGHT_AREA)
        ) {
            val isHighlight = activeTool == AnnotationTool.HIGHLIGHT_AREA
            drawAnnotationPayload(
                AnnotationPayload.ShapeItem(
                    shapeType = activeTool,
                    start = start,
                    end = end,
                    strokeColor = activeColor,
                    fillColor = if (isHighlight) activeColor else null,
                    strokeWidth = activeStrokeWidth,
                    alpha = if (isHighlight) 0.3f else activeAlpha
                )
            )
        }
    }
}

private fun DrawScope.drawAnnotationPayload(payload: AnnotationPayload) {
    when (payload) {
        is AnnotationPayload.FreehandStroke -> {
            if (payload.points.size < 2) return
            val path = Path()
            val first = payload.points.first()
            path.moveTo(first.x * size.width, first.y * size.height)
            payload.points.drop(1).forEach { p ->
                path.lineTo(p.x * size.width, p.y * size.height)
            }
            drawPath(
                path = path,
                color = Color(payload.color).copy(alpha = payload.alpha),
                style = Stroke(
                    width = payload.strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        is AnnotationPayload.ShapeItem -> {
            val x0 = payload.start.x * size.width
            val y0 = payload.start.y * size.height
            val x1 = payload.end.x * size.width
            val y1 = payload.end.y * size.height
            val left = min(x0, x1)
            val top = min(y0, y1)
            val w = kotlin.math.abs(x1 - x0)
            val h = kotlin.math.abs(y1 - y0)
            val strokeColor = Color(payload.strokeColor).copy(alpha = payload.alpha)
            val fillColor = payload.fillColor?.let { Color(it).copy(alpha = payload.alpha) }

            when (payload.shapeType) {
                AnnotationTool.RECTANGLE, AnnotationTool.HIGHLIGHT_AREA -> {
                    if (fillColor != null) {
                        drawRect(color = fillColor, topLeft = Offset(left, top), size = Size(w, h))
                    }
                    drawRect(
                        color = strokeColor,
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        style = Stroke(width = payload.strokeWidth)
                    )
                }
                AnnotationTool.ELLIPSE -> {
                    if (fillColor != null) {
                        drawOval(color = fillColor, topLeft = Offset(left, top), size = Size(w, h))
                    }
                    drawOval(
                        color = strokeColor,
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        style = Stroke(width = payload.strokeWidth)
                    )
                }
                AnnotationTool.LINE -> {
                    drawLine(
                        color = strokeColor,
                        start = Offset(x0, y0),
                        end = Offset(x1, y1),
                        strokeWidth = payload.strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
                AnnotationTool.ARROW -> {
                    drawLine(
                        color = strokeColor,
                        start = Offset(x0, y0),
                        end = Offset(x1, y1),
                        strokeWidth = payload.strokeWidth,
                        cap = StrokeCap.Round
                    )
                    val angle = atan2(y1 - y0, x1 - x0)
                    val arrowLen = 16f
                    val a1 = angle + Math.PI * 0.85
                    val a2 = angle - Math.PI * 0.85
                    drawLine(
                        color = strokeColor,
                        start = Offset(x1, y1),
                        end = Offset(x1 + (arrowLen * cos(a1)).toFloat(), y1 + (arrowLen * sin(a1)).toFloat()),
                        strokeWidth = payload.strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = strokeColor,
                        start = Offset(x1, y1),
                        end = Offset(x1 + (arrowLen * cos(a2)).toFloat(), y1 + (arrowLen * sin(a2)).toFloat()),
                        strokeWidth = payload.strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
                else -> Unit
            }
        }
        is AnnotationPayload.TextItem -> {
            // Text rendering requires native canvas; shapes/strokes are primary for now.
        }
    }
}
