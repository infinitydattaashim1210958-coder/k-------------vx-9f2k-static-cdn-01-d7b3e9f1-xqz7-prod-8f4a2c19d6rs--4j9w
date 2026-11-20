package com.cloudshelf.reader.feature.reader

import com.cloudshelf.reader.domain.model.AnnotationExportBundle
import com.cloudshelf.reader.domain.model.AnnotationItem
import com.cloudshelf.reader.domain.model.AnnotationPayload
import com.cloudshelf.reader.domain.model.AnnotationTool
import com.cloudshelf.reader.domain.model.AnnotationType
import com.cloudshelf.reader.domain.model.NormRect
import com.cloudshelf.reader.domain.model.NormalizedPoint
import com.cloudshelf.reader.domain.model.RenderedAnnotation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Owns annotation serialization, hit-testing, move/resize transforms, undo/redo stack,
 * and JSON import/export (Section 6 of Okular Compatibility Roadmap).
 */
object AnnotationEngine {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun toDomainItem(rendered: RenderedAnnotation): AnnotationItem {
        val type = when (rendered.payload) {
            is AnnotationPayload.FreehandStroke -> AnnotationType.DRAWING
            is AnnotationPayload.ShapeItem -> when (rendered.payload.shapeType) {
                AnnotationTool.HIGHLIGHT_AREA,
                AnnotationTool.HIGHLIGHTER_STROKE -> AnnotationType.HIGHLIGHT
                else -> AnnotationType.SHAPE
            }
            is AnnotationPayload.TextItem -> AnnotationType.TEXT_BOX
            is AnnotationPayload.PopupNoteItem -> AnnotationType.COMMENT
            is AnnotationPayload.StampItem -> AnnotationType.SHAPE
        }
        return AnnotationItem(
            id = rendered.id,
            bookId = rendered.bookId,
            pageNumber = rendered.pageNumber,
            type = type,
            payloadJson = json.encodeToString(rendered.payload),
            note = when (val p = rendered.payload) {
                is AnnotationPayload.TextItem -> p.text
                is AnnotationPayload.PopupNoteItem -> p.text
                is AnnotationPayload.StampItem -> p.label
                else -> null
            },
            updatedAtEpochMs = rendered.updatedAtEpochMs
        )
    }

    fun fromDomainItem(item: AnnotationItem): RenderedAnnotation? {
        val payload = runCatching { json.decodeFromString<AnnotationPayload>(item.payloadJson) }.getOrNull()
            ?: return null
        return RenderedAnnotation(
            id = item.id,
            bookId = item.bookId,
            pageNumber = item.pageNumber,
            payload = payload,
            updatedAtEpochMs = item.updatedAtEpochMs
        )
    }

    fun exportBundleJson(bookId: String, annotations: List<RenderedAnnotation>): String {
        return json.encodeToString(
            AnnotationExportBundle(
                bookId = bookId,
                annotations = annotations
            )
        )
    }

    fun importBundleJson(rawJson: String, targetBookId: String): List<RenderedAnnotation> {
        val bundle = json.decodeFromString<AnnotationExportBundle>(rawJson)
        return bundle.annotations.map { it.copy(bookId = targetBookId) }
    }

    fun boundsOf(payload: AnnotationPayload): NormRect {
        return when (payload) {
            is AnnotationPayload.FreehandStroke -> {
                if (payload.points.isEmpty()) return NormRect(0f, 0f, 0f, 0f)
                var minX = 1f
                var minY = 1f
                var maxX = 0f
                var maxY = 0f
                for (p in payload.points) {
                    minX = min(minX, p.x)
                    minY = min(minY, p.y)
                    maxX = max(maxX, p.x)
                    maxY = max(maxY, p.y)
                }
                NormRect(minX, minY, maxX, maxY)
            }
            is AnnotationPayload.ShapeItem -> NormRect(
                left = min(payload.start.x, payload.end.x),
                top = min(payload.start.y, payload.end.y),
                right = max(payload.start.x, payload.end.x),
                bottom = max(payload.start.y, payload.end.y)
            )
            is AnnotationPayload.TextItem -> NormRect(
                left = payload.topLeft.x,
                top = payload.topLeft.y,
                right = (payload.topLeft.x + payload.widthNorm).coerceIn(0f, 1f),
                bottom = (payload.topLeft.y + payload.heightNorm).coerceIn(0f, 1f)
            )
            is AnnotationPayload.PopupNoteItem -> NormRect(
                left = (payload.anchor.x - 0.025f).coerceIn(0f, 1f),
                top = (payload.anchor.y - 0.025f).coerceIn(0f, 1f),
                right = (payload.anchor.x + 0.025f).coerceIn(0f, 1f),
                bottom = (payload.anchor.y + 0.025f).coerceIn(0f, 1f)
            )
            is AnnotationPayload.StampItem -> NormRect(
                left = payload.anchor.x,
                top = payload.anchor.y,
                right = (payload.anchor.x + 0.18f).coerceIn(0f, 1f),
                bottom = (payload.anchor.y + 0.06f).coerceIn(0f, 1f)
            )
        }
    }

    fun hitTest(payload: AnnotationPayload, point: NormalizedPoint, tolerance: Float = 0.03f): Boolean {
        return when (payload) {
            is AnnotationPayload.FreehandStroke -> {
                if (payload.points.size == 1) {
                    val p0 = payload.points.first()
                    hypot(point.x - p0.x, point.y - p0.y) <= tolerance
                } else {
                    payload.points.zipWithNext().any { (a, b) ->
                        pointToSegmentDistance(point, a, b) <= tolerance
                    }
                }
            }
            is AnnotationPayload.ShapeItem -> {
                if (payload.shapeType == AnnotationTool.LINE || payload.shapeType == AnnotationTool.ARROW) {
                    pointToSegmentDistance(point, payload.start, payload.end) <= tolerance
                } else {
                    val b = boundsOf(payload)
                    point.x in (b.left - tolerance)..(b.right + tolerance) &&
                        point.y in (b.top - tolerance)..(b.bottom + tolerance)
                }
            }
            else -> {
                val b = boundsOf(payload)
                point.x in (b.left - tolerance)..(b.right + tolerance) &&
                    point.y in (b.top - tolerance)..(b.bottom + tolerance)
            }
        }
    }

    fun translate(payload: AnnotationPayload, dx: Float, dy: Float): AnnotationPayload {
        fun shift(p: NormalizedPoint) = NormalizedPoint(
            x = (p.x + dx).coerceIn(0f, 1f),
            y = (p.y + dy).coerceIn(0f, 1f)
        )
        return when (payload) {
            is AnnotationPayload.FreehandStroke -> payload.copy(points = payload.points.map(::shift))
            is AnnotationPayload.ShapeItem -> payload.copy(start = shift(payload.start), end = shift(payload.end))
            is AnnotationPayload.TextItem -> payload.copy(topLeft = shift(payload.topLeft))
            is AnnotationPayload.PopupNoteItem -> payload.copy(anchor = shift(payload.anchor))
            is AnnotationPayload.StampItem -> payload.copy(anchor = shift(payload.anchor))
        }
    }

    private fun pointToSegmentDistance(p: NormalizedPoint, a: NormalizedPoint, b: NormalizedPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}

class AnnotationUndoRedoManager(private val maxDepth: Int = 40) {
    private val undoStack = ArrayDeque<List<RenderedAnnotation>>()
    private val redoStack = ArrayDeque<List<RenderedAnnotation>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun recordBeforeMutation(snapshot: List<RenderedAnnotation>) {
        if (undoStack.size >= maxDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot.toList())
        redoStack.clear()
    }

    fun undo(current: List<RenderedAnnotation>): List<RenderedAnnotation>? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current.toList())
        return prev
    }

    fun redo(current: List<RenderedAnnotation>): List<RenderedAnnotation>? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current.toList())
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
