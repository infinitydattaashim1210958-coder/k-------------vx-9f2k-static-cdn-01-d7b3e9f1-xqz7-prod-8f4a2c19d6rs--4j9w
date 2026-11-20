# Okular Feature Compatibility Matrix — CloudShelf (Updated)

Updated after implementing the P0–P6 architecture subsystems (`DocumentEngine`, `ReaderController`, `AnnotationEngine`, `OnlinePdfCacheManager`, `CloudShelfDatabase` v2, `PdfDocumentTools`, `ReaderTtsController`).

> **Verification notice:** Kotlin/Android files were authored to match the project's Gradle/KSP/Hilt/Room/Compose toolchain. Full Gradle compilation (`./gradlew testDebugUnitTest assembleDebug`) must be executed in Android Studio or CI where the Android SDK and JDK 17 are installed.

## Key implemented subsystems

- **P0 Reliability & Data Safety**:
  - `PdfReaderViewModel` now restores persisted `lastPage` via `UserActivityRepository.getProgressOnce(bookId)` + `ReaderPageRestoration.resolveInitialPage`.
  - `PdfDocumentEngine` serializes `PdfRenderer` / `Page` access with a `Mutex`, cancels superseded page render jobs, and stores rendered bitmaps in `BoundedBitmapLruCache`.
  - `OnlinePdfCacheManager` downloads to `.tmp.part`, validates expected byte count & SHA-256, checks redirect host allowlist, and cleans up partial files on error/cancellation.
  - `CloudShelfDatabase` upgraded to `version = 2` with `exportSchema = true`, `MIGRATION_1_2`, and KSP `room.schemaLocation`.
- **P1–P6 Reader, Annotations & Multi-Format**:
  - `ReaderLayoutMode` (`SINGLE_PAGE`, `CONTINUOUS_VERTICAL`, `FACING_PAGES`), zoom controls, 90° rotation, fullscreen, thumbnails rail, bookmarks bottom sheet, document metadata dialog, print (`PdfDocumentTools`), selected-page PDF export, `ReaderTtsController`, keyboard shortcuts (`onPreviewKeyEvent`), and Screen-reader semantics.
  - `AnnotationEngine` + `AnnotationCanvasOverlay` wired to `UserActivityRepository` with normalized `[0..1]` coordinates, `StaticLayout` text box rendering, popup notes, stamps, hit-test selection/move, eraser, color/width/opacity controls, JSON export/import, and `AnnotationUndoRedoManager`.
  - `DocumentEngineRegistry` with `PdfDocumentEngine`, `ComicDocumentEngine` (CBZ), `EpubDocumentEngine`, `MarkdownDocumentEngine`, and `ImageDocumentEngine`.
