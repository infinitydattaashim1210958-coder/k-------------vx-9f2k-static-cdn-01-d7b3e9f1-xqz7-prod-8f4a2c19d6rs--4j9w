package com.cloudshelf.reader.feature.reader

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cloudshelf.reader.domain.model.AnnotationPayload
import com.cloudshelf.reader.domain.model.AnnotationTool
import com.cloudshelf.reader.domain.model.NormalizedPoint
import com.cloudshelf.reader.domain.model.RenderedAnnotation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-featured Compose annotation overlay integrated into normalized [0..1] PDF page space.
 * Supports freehand strokes, shapes, text boxes (with native StaticLayout rendering), popup notes,
 * stamps, hit-test selection/move, and eraser deletion.
 */
@Composable
fun AnnotationCanvasOverlay(
    annotations: List<RenderedAnnotation>,
    activeTool: AnnotationTool,
    activeColorArgb: Long,
    activeStrokeWidth: Float,
    activeAlpha: Float,
    selectedAnnotationId: String?,
    onSelectAnnotation: (String?) -> Unit,
    onCreateAnnotation: (AnnotationPayload) -> Unit,
    onMoveAnnotation: (id: String, dxNorm: Float, dyNorm: Float) -> Unit,
    onEraseAt: (NormalizedPoint) -> Unit,
    onRequestTextInput: (NormalizedPoint, isPopupNote: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeStrokePoints by remember { mutableStateOf<List<NormalizedPoint>>(emptyList()) }
    var shapeStart by remember { mutableStateOf<NormalizedPoint?>(null) }
    var shapeEnd by remember { mutableStateOf<NormalizedPoint?>(null) }

    val overlayModifier = modifier
        .fillMaxSize()
        .semantics {
            contentDescription = "Page annotation canvas with ${annotations.size} annotations"
        }
        .pointerInput(activeTool, annotations, selectedAnnotationId) {
            if (activeTool == AnnotationTool.NONE) return@pointerInput

            detectTapGestures { offset ->
                val norm = NormalizedPoint(
                    x = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    y = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                )
                when (activeTool) {
                    AnnotationTool.SELECT -> {
                        val hit = annotations.lastOrNull { AnnotationEngine.hitTest(it.payload, norm) }
                        onSelectAnnotation(hit?.id)
                    }
                    AnnotationTool.ERASER -> onEraseAt(norm)
                    AnnotationTool.TEXT_BOX -> onRequestTextInput(norm, false)
                    AnnotationTool.POPUP_NOTE -> onRequestTextInput(norm, true)
                    AnnotationTool.STAMP -> onCreateAnnotation(
                        AnnotationPayload.StampItem(
                            anchor = norm,
                            label = "APPROVED",
                            color = activeColorArgb
                        )
                    )
                    else -> Unit
                }
            }
        }
        .pointerInput(activeTool, activeColorArgb, activeStrokeWidth, activeAlpha, selectedAnnotationId) {
            if (activeTool == AnnotationTool.NONE ||
                activeTool == AnnotationTool.TEXT_BOX ||
                activeTool == AnnotationTool.POPUP_NOTE ||
                activeTool == AnnotationTool.STAMP
            ) return@pointerInput

            detectDragGestures(
                onDragStart = { offset ->
                    val norm = NormalizedPoint(
                        x = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        y = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    )
                    when (activeTool) {
                        AnnotationTool.PEN,
                        AnnotationTool.PENCIL,
                        AnnotationTool.HIGHLIGHTER_STROKE -> {
                            activeStrokePoints = listOf(norm)
                        }
                        AnnotationTool.RECTANGLE,
                        AnnotationTool.ELLIPSE,
                        AnnotationTool.LINE,
                        AnnotationTool.ARROW,
                        AnnotationTool.HIGHLIGHT_AREA,
                        AnnotationTool.UNDERLINE,
                        AnnotationTool.STRIKEOUT,
                        AnnotationTool.SQUIGGLY -> {
                            shapeStart = norm
                            shapeEnd = norm
                        }
                        AnnotationTool.ERASER -> onEraseAt(norm)
                        AnnotationTool.SELECT -> {
                            if (selectedAnnotationId == null) {
                                val hit = annotations.lastOrNull { AnnotationEngine.hitTest(it.payload, norm) }
                                onSelectAnnotation(hit?.id)
                            }
                        }
                        else -> Unit
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val pos = change.position
                    val norm = NormalizedPoint(
                        x = (pos.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        y = (pos.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    )
                    when (activeTool) {
                        AnnotationTool.PEN,
                        AnnotationTool.PENCIL,
                        AnnotationTool.HIGHLIGHTER_STROKE -> {
                            activeStrokePoints = activeStrokePoints + norm
                        }
                        AnnotationTool.RECTANGLE,
                        AnnotationTool.ELLIPSE,
                        AnnotationTool.LINE,
                        AnnotationTool.ARROW,
                        AnnotationTool.HIGHLIGHT_AREA,
                        AnnotationTool.UNDERLINE,
                        AnnotationTool.STRIKEOUT,
                        AnnotationTool.SQUIGGLY -> {
                            shapeEnd = norm
                        }
                        AnnotationTool.ERASER -> onEraseAt(norm)
                        AnnotationTool.SELECT -> {
                            selectedAnnotationId?.let { selId ->
                                onMoveAnnotation(
                                    selId,
                                    dragAmount.x / size.width.coerceAtLeast(1),
                                    dragAmount.y / size.height.coerceAtLeast(1)
                                )
                            }
                        }
                        else -> Unit
                    }
                },
                onDragEnd = {
                    when (activeTool) {
                        AnnotationTool.PEN,
                        AnnotationTool.PENCIL,
                        AnnotationTool.HIGHLIGHTER_STROKE -> {
                            if (activeStrokePoints.size >= 2) {
                                val effectiveAlpha =
                                    if (activeTool == AnnotationTool.HIGHLIGHTER_STROKE) min(0.4f, activeAlpha) else activeAlpha
                                val effectiveWidth =
                                    if (activeTool == AnnotationTool.HIGHLIGHTER_STROKE) activeStrokeWidth * 3.5f else activeStrokeWidth
                                onCreateAnnotation(
                                    AnnotationPayload.FreehandStroke(
                                        points = activeStrokePoints,
                                        color = activeColorArgb,
                                        strokeWidth = effectiveWidth,
                                        alpha = effectiveAlpha,
                                        tool = activeTool
                                    )
                                )
                            }
                            activeStrokePoints = emptyList()
                        }
                        AnnotationTool.RECTANGLE,
                        AnnotationTool.ELLIPSE,
                        AnnotationTool.LINE,
                        AnnotationTool.ARROW,
                        AnnotationTool.HIGHLIGHT_AREA,
                        AnnotationTool.UNDERLINE,
                        AnnotationTool.STRIKEOUT,
                        AnnotationTool.SQUIGGLY -> {
                            val s = shapeStart
                            val e = shapeEnd
                            if (s != null && e != null) {
                                val isHighlight = activeTool == AnnotationTool.HIGHLIGHT_AREA
                                onCreateAnnotation(
                                    AnnotationPayload.ShapeItem(
                                        shapeType = activeTool,
                                        start = s,
                                        end = e,
                                        strokeColor = activeColorArgb,
                                        fillColor = if (isHighlight) activeColorArgb else null,
                                        strokeWidth = activeStrokeWidth,
                                        alpha = if (isHighlight) 0.35f else activeAlpha
                                    )
                                )
                            }
                            shapeStart = null
                            shapeEnd = null
                        }
                        else -> Unit
                    }
                },
                onDragCancel = {
                    activeStrokePoints = emptyList()
                    shapeStart = null
                    shapeEnd = null
                }
            )
        }

    Canvas(modifier = overlayModifier) {
        annotations.forEach { item ->
            drawAnnotationPayload(item.payload, isSelected = item.id == selectedAnnotationId)
        }

        if (activeStrokePoints.size >= 2) {
            val effectiveAlpha =
                if (activeTool == AnnotationTool.HIGHLIGHTER_STROKE) min(0.4f, activeAlpha) else activeAlpha
            val effectiveWidth =
                if (activeTool == AnnotationTool.HIGHLIGHTER_STROKE) activeStrokeWidth * 3.5f else activeStrokeWidth
            drawAnnotationPayload(
                AnnotationPayload.FreehandStroke(
                    points = activeStrokePoints,
                    color = activeColorArgb,
                    strokeWidth = effectiveWidth,
                    alpha = effectiveAlpha,
                    tool = activeTool
                ),
                isSelected = false
            )
        }

        val s = shapeStart
        val e = shapeEnd
        if (s != null && e != null) {
            val isHighlight = activeTool == AnnotationTool.HIGHLIGHT_AREA
            drawAnnotationPayload(
                AnnotationPayload.ShapeItem(
                    shapeType = activeTool,
                    start = s,
                    end = e,
                    strokeColor = activeColorArgb,
                    fillColor = if (isHighlight) activeColorArgb else null,
                    strokeWidth = activeStrokeWidth,
                    alpha = if (isHighlight) 0.35f else activeAlpha
                ),
                isSelected = false
            )
        }
    }
}

private fun DrawScope.drawAnnotationPayload(payload: AnnotationPayload, isSelected: Boolean) {
    when (payload) {
        is AnnotationPayload.FreehandStroke -> {
            if (payload.points.size < 2) return
            val path = Path().apply {
                val p0 = payload.points.first()
                moveTo(p0.x * size.width, p0.y * size.height)
                for (i in 1 until payload.points.size) {
                    val pt = payload.points[i]
                    lineTo(pt.x * size.width, pt.y * size.height)
                }
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
            val w = max(2f, kotlin.math.abs(x1 - x0))
            val h = max(2f, kotlin.math.abs(y1 - y0))
            val strokeColor = Color(payload.strokeColor).copy(alpha = payload.alpha)
            val fillColor = payload.fillColor?.let { Color(it).copy(alpha = payload.alpha) }

            when (payload.shapeType) {
                AnnotationTool.RECTANGLE,
                AnnotationTool.HIGHLIGHT_AREA -> {
                    if (fillColor != null) {
                        drawRect(color = fillColor, topLeft = Offset(left, top), size = Size(w, h))
                    }
                    if (payload.shapeType == AnnotationTool.RECTANGLE) {
                        drawRect(
                            color = strokeColor,
                            topLeft = Offset(left, top),
                            size = Size(w, h),
                            style = Stroke(width = payload.strokeWidth)
                        )
                    }
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

                AnnotationTool.LINE,
                AnnotationTool.UNDERLINE,
                AnnotationTool.STRIKEOUT -> {
                    val lineY = if (payload.shapeType == AnnotationTool.UNDERLINE) y1 else y0
                    val endY = if (payload.shapeType == AnnotationTool.UNDERLINE) y1 else y1
                    drawLine(
                        color = strokeColor,
                        start = Offset(x0, lineY),
                        end = Offset(x1, endY),
                        strokeWidth = payload.strokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                AnnotationTool.SQUIGGLY -> {
                    val path = Path().apply {
                        moveTo(left, y1)
                        var cx = left
                        var up = true
                        val step = 10f
                        while (cx < left + w) {
                            val nx = min(cx + step, left + w)
                            val ny = y1 + if (up) -4f else 4f
                            lineTo(nx, ny)
                            cx = nx
                            up = !up
                        }
                    }
                    drawPath(
                        path = path,
                        color = strokeColor,
                        style = Stroke(width = payload.strokeWidth, cap = StrokeCap.Round)
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
                    val arrowLen = 18f
                    val a1 = angle + Math.PI * 0.85
                    val a2 = angle - Math.PI * 0.85
                    drawLine(
                        color = strokeColor,
                        start = Offset(x1, y1),
                        end = Offset(
                            x1 + (arrowLen * cos(a1)).toFloat(),
                            y1 + (arrowLen * sin(a1)).toFloat()
                        ),
                        strokeWidth = payload.strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = strokeColor,
                        start = Offset(x1, y1),
                        end = Offset(
                            x1 + (arrowLen * cos(a2)).toFloat(),
                            y1 + (arrowLen * sin(a2)).toFloat()
                        ),
                        strokeWidth = payload.strokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                else -> Unit
            }
        }

        is AnnotationPayload.TextItem -> {
            val left = payload.topLeft.x * size.width
            val top = payload.topLeft.y * size.height
            val boxWidth = (payload.widthNorm * size.width).coerceAtLeast(120f)
            val boxHeight = (payload.heightNorm * size.height).coerceAtLeast(44f)

            payload.backgroundColor?.let { bg ->
                drawRoundRect(
                    color = Color(bg),
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(8f, 8f)
                )
            }
            drawRoundRect(
                color = Color(payload.color).copy(alpha = 0.6f),
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 2f)
            )
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = payload.color.toInt()
                textSize = payload.fontSizeSp * 2.2f
            }
            val layout = StaticLayout.Builder.obtain(
                payload.text,
                0,
                payload.text.length,
                textPaint,
                (boxWidth - 16f).toInt().coerceAtLeast(40)
            ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            drawContext.canvas.nativeCanvas.save()
            drawContext.canvas.nativeCanvas.translate(left + 8f, top + 8f)
            layout.draw(drawContext.canvas.nativeCanvas)
            drawContext.canvas.nativeCanvas.restore()
        }

        is AnnotationPayload.PopupNoteItem -> {
            val cx = payload.anchor.x * size.width
            val cy = payload.anchor.y * size.height
            drawRoundRect(
                color = Color(payload.color),
                topLeft = Offset(cx - 14f, cy - 14f),
                size = Size(28f, 28f),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cx - 8f, cy - 5f),
                size = Size(16f, 2.5f),
                cornerRadius = CornerRadius(1f, 1f)
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cx - 8f, cy + 1f),
                size = Size(12f, 2.5f),
                cornerRadius = CornerRadius(1f, 1f)
            )
        }

        is AnnotationPayload.StampItem -> {
            val left = payload.anchor.x * size.width
            val top = payload.anchor.y * size.height
            val w = 0.18f * size.width
            val h = 0.055f * size.height
            val stampColor = Color(payload.color)
            drawRoundRect(
                color = stampColor.copy(alpha = 0.12f),
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = stampColor,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 3f)
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = payload.color.toInt()
                textSize = (h * 0.45f).coerceIn(16f, 36f)
                isFakeBoldText = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                payload.label,
                left + 12f,
                top + h * 0.68f,
                paint
            )
        }
    }

    if (isSelected) {
        val bounds = AnnotationEngine.boundsOf(payload)
        drawRect(
            color = Color(0xFF1B6EF3),
            topLeft = Offset(bounds.left * size.width - 4f, bounds.top * size.height - 4f),
            size = Size(
                max(16f, (bounds.right - bounds.left) * size.width + 8f),
                max(16f, (bounds.bottom - bounds.top) * size.height + 8f)
            ),
            style = Stroke(
                width = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
            )
        )
    }
}
