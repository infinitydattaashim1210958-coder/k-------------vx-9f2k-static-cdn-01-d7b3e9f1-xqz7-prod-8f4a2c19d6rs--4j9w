package com.cloudshelf.reader

import com.cloudshelf.reader.domain.model.AnnotationPayload
import com.cloudshelf.reader.domain.model.AnnotationTool
import com.cloudshelf.reader.domain.model.NormalizedPoint
import com.cloudshelf.reader.domain.model.RenderedAnnotation
import com.cloudshelf.reader.feature.reader.AnnotationEngine
import com.cloudshelf.reader.feature.reader.AnnotationUndoRedoManager
import com.cloudshelf.reader.feature.reader.ReaderNavigationHistory
import com.cloudshelf.reader.feature.reader.ReaderPageRestoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAndAnnotationTest {

    @Test
    fun initialPageRestorationClampsAndPreservesSavedPage() {
        assertEquals(0, ReaderPageRestoration.resolveInitialPage(null, 100))
        assertEquals(42, ReaderPageRestoration.resolveInitialPage(42, 190))
        assertEquals(9, ReaderPageRestoration.resolveInitialPage(42, 10))
        assertEquals(0, ReaderPageRestoration.resolveInitialPage(-3, 10))
    }

    @Test
    fun navigationHistoryTracksBackAndForwardStacks() {
        val history = ReaderNavigationHistory(0)
        history.navigateTo(5)
        history.navigateTo(18)
        assertTrue(history.canGoBack)
        assertFalse(history.canGoForward)

        assertEquals(5, history.back())
        assertTrue(history.canGoForward)
        assertEquals(0, history.back())
        assertNull(history.back())
        assertEquals(5, history.forward())
    }

    @Test
    fun annotationEngineHitTestTranslateAndJsonRoundTrip() {
        val rect = AnnotationPayload.ShapeItem(
            shapeType = AnnotationTool.RECTANGLE,
            start = NormalizedPoint(0.2f, 0.2f),
            end = NormalizedPoint(0.5f, 0.5f),
            strokeColor = 0xFF1B6EF3L,
            strokeWidth = 4f
        )
        assertTrue(AnnotationEngine.hitTest(rect, NormalizedPoint(0.35f, 0.35f)))
        assertFalse(AnnotationEngine.hitTest(rect, NormalizedPoint(0.9f, 0.9f)))

        val moved = AnnotationEngine.translate(rect, 0.1f, 0.1f) as AnnotationPayload.ShapeItem
        assertEquals(0.3f, moved.start.x, 0.001f)
        assertEquals(0.6f, moved.end.y, 0.001f)

        val item = RenderedAnnotation(id = "a1", bookId = "b1", pageNumber = 2, payload = moved)
        val domain = AnnotationEngine.toDomainItem(item)
        val restored = AnnotationEngine.fromDomainItem(domain)
        assertNotNull(restored)
        assertEquals("a1", restored?.id)

        val bundleJson = AnnotationEngine.exportBundleJson("b1", listOf(item))
        val imported = AnnotationEngine.importBundleJson(bundleJson, "b2")
        assertEquals(1, imported.size)
        assertEquals("b2", imported.first().bookId)
    }

    @Test
    fun annotationUndoRedoRestoresSnapshots() {
        val manager = AnnotationUndoRedoManager()
        val item = RenderedAnnotation(
            id = "a1",
            bookId = "b1",
            pageNumber = 0,
            payload = AnnotationPayload.TextItem(
                topLeft = NormalizedPoint(0.1f, 0.1f),
                text = "Note",
                color = 0xFF000000L
            )
        )
        var list = emptyList<RenderedAnnotation>()
        manager.recordBeforeMutation(list)
        list = listOf(item)
        assertTrue(manager.canUndo)

        val undone = manager.undo(list)
        assertNotNull(undone)
        assertEquals(0, undone?.size)
        assertTrue(manager.canRedo)

        val redone = manager.redo(undone!!)
        assertEquals(1, redone?.size)
    }
}
