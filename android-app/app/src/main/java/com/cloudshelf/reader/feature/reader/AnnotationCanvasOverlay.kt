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
import androidx.compose.ui.Modifier
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

@Composable
fun AnnotationCanvasOverlay(
    activeTool: AnnotationTool,
    activeColorArgb: Long,
    activeAlpha: Float,
    strokeWidthRatio: Float,
    committedAnnotations: List<AnnotationPayload>,
    onAnnotationCreated: (AnnotationPayload) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeStrokePoints = remember { mutableStateListOf<NormalizedPoint>() }
    var dragStart by remember { mutableStateOf<NormalizedPoint?>(null) }
    var dragCurrent by remember { mutableStateOf<NormalizedPoint?>(null) }

    val gestureModifier = if (activeTool == AnnotationTool.NONE) {
        Modifier
    } else {
        Modifier.pointerInput(activeTool, activeColorArgb, activeAlpha, strokeWidthRatio) {
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
                    change.consume()
                    val norm = NormalizedPoint(
                        x = (change.position.x / size.width).coerceIn(0f, 1f),
                        y = (change.position.y / size.height).coerceIn(0f, 1f)
                    )
                    dragCurrent = norm
                    activeStrokePoints.add(norm)
                },
                onDragEnd = {
                    val start = dragStart
                    val end = dragCurrent
                    when (activeTool) {
                        AnnotationTool.PEN,
                        AnnotationTool.PENCIL,
                        AnnotationTool.HIGHLIGHTER_STROKE -> {
                            if (activeStrokePoints.size >= 2) {
                                onAnnotationCreated(
                                    AnnotationPayload.FreehandStroke(
                                        points = activeStrokePoints.toList(),
                                        colorArgb = activeColorArgb,
                                        strokeWidthRatio = strokeWidthRatio,
                                        alpha = if (activeTool == AnnotationTool.HIGHLIGHTER_STROKE) 0.35f else activeAlpha
                                    )
                                )
                            }
                        }
                        AnnotationTool.RECTANGLE,
                        AnnotationTool.ELLIPSE,
                        AnnotationTool.LINE,
                        AnnotationTool.ARROW,
                        AnnotationTool.HIGHLIGHT_AREA -> {
                            if (start != null && end != null) {
                                val isHighlight = activeTool == AnnotationTool.HIGHLIGHT_AREA
                                onAnnotationCreated(
                                    AnnotationPayload.ShapeItem(
                                        shapeType = activeTool.name,
                                        start = start,
                                        end = end,
                                        strokeColorArgb = activeColorArgb,
                                        fillColorArgb = if (isHighlight) activeColorArgb else null,
                                        strokeWidthRatio = strokeWidthRatio,
                                        alpha = if (isHighlight) 0.35f else activeAlpha
                                    )
                                )
                            }
                        }
                        else -> Unit
                    }
                    activeStrokePoints.clear()
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

        // Render live in-progress gesture
        if (activeStrokePoints.size >= 2 &&
            (activeTool == AnnotationTool.PEN ||
                activeTool == AnnotationTool.PENCIL ||
                activeTool == AnnotationTool.HIGHLIGHTER_STROKE)
        ) {
            drawAnnotationPayload(
                AnnotationPayload.FreehandStroke(
                    points = activeStrokePoints.toList(),
                    colorArgb = activeColorArgb,
                    strokeWidthRatio = strokeWidthRatio,
                    alpha = if (activeTool == AnnotationTool.HIGHLIGHTER_STROKE) 0.35f else activeAlpha
                )
            )
        } else if (dragStart != null && dragCurrent != null &&
            (activeTool == AnnotationTool.RECTANGLE ||
                activeTool == AnnotationTool.ELLIPSE ||
                activeTool == AnnotationTool.LINE ||
                activeTool == AnnotationTool.ARROW ||
                activeTool == AnnotationTool.HIGHLIGHT_AREA)
        ) {
            val isHighlight = activeTool == AnnotationTool.HIGHLIGHT_AREA
            drawAnnotationPayload(
                AnnotationPayload.ShapeItem(
                    shapeType = activeTool.name,
                    start = dragStart!!,
                    end = dragCurrent!!,
                    strokeColorArgb = activeColorArgb,
                    fillColorArgb = if (isHighlight) activeColorArgb else null,
                    strokeWidthRatio = strokeWidthRatio,
                    alpha = if (isHighlight) 0.35f else activeAlpha
                )
            )
        }
    }
}

private fun DrawScope.drawAnnotationPayload(payload: AnnotationPayload) {
    when (payload) {
        is AnnotationPayload.FreehandStroke -> {
            if (payload.points.size < 2) return
            val path = Path().apply {
                moveTo(payload.points[0].x * size.width, payload.points[0].y * size.height)
                for (i in 1 until payload.points.size) {
                    lineTo(payload.points[i].x * size.width, payload.points[i].y * size.height)
                }
            }
            drawPath(
                path = path,
                color = Color(payload.colorArgb).copy(alpha = payload.alpha),
                style = Stroke(
                    width = (payload.strokeWidthRatio * size.width).coerceAtLeast(2f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        is AnnotationPayload.ShapeItem -> {
            val left = min(payload.start.x, payload.end.x) * size.width
            val top = min(payload.start.y, payload.end.y) * size.height
            val width = kotlin.math.abs(payload.end.x - payload.start.x) * size.width
            val height = kotlin.math.abs(payload.end.y - payload.start.y) * size.height
            val strokeColor = Color(payload.strokeColorArgb).copy(alpha = payload.alpha)
            val strokePx = (payload.strokeWidthRatio * size.width).coerceAtLeast(2f)

            when (payload.shapeType) {
                AnnotationTool.RECTANGLE.name,
                AnnotationTool.HIGHLIGHT_AREA.name -> {
                    payload.fillColorArgb?.let { fill ->
                        drawRect(
                            color = Color(fill).copy(alpha = payload.alpha),
                            topLeft = Offset(left, top),
                            size = Size(width, height)
                        )
                    }
                    if (payload.shapeType == AnnotationTool.RECTANGLE.name) {
                        drawRect(
                            color = strokeColor,
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            style = Stroke(width = strokePx)
                        )
                    }
                }
                AnnotationTool.ELLIPSE.name -> {
                    drawOval(
                        color = strokeColor,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = strokePx)
                    )
                }
                AnnotationTool.LINE.name,
                AnnotationTool.ARROW.name -> {
                    val p1 = Offset(payload.start.x * size.width, payload.start.y * size.height)
                    val p2 = Offset(payload.end.x * size.width, payload.end.y * size.height)
                    drawLine(
                        color = strokeColor,
                        start = p1,
                        end = p2,
                      strokeWidth = strokePx,
cap = StrokeCap.Round
)
if (payload.shapeType == AnnotationTool.ARROW.name) {
val angle = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())
val headLen = strokePx * 4f
val wing1 = Offset(
(p2.x - headLen * cos(angle - Math.PI / 6)).toFloat(),
(p2.y - headLen * sin(angle - Math.PI / 6)).toFloat()
)
val wing2 = Offset(
(p2.x - headLen * cos(angle + Math.PI / 6)).toFloat(),
(p2.y - headLen * sin(angle + Math.PI / 6)).toFloat()
)
drawLine(strokeColor, p2, wing1, strokePx, StrokeCap.Round)
drawLine(strokeColor, p2, wing2, strokePx, StrokeCap.Round)
}
}
}
}
is AnnotationPayload.CommentPinItem -> {
val center = Offset(payload.anchor.x * size.width, payload.anchor.y * size.height)
drawCircle(
color = Color(payload.colorArgb),
radius = 18f,
center = center
)
}
is AnnotationPayload.TextBoxItem -> {
// TextBox composables are positioned interactively over the page bounds
}
}
}
