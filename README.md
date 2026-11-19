# CloudShelf — Native Android PDF Library & Reader

Production-oriented Kotlin + Jetpack Compose application that treats a GitHub repository + Releases as a cloud PDF catalogue.

## Repository layout

- `android-app/` — full Android project (open this folder in Android Studio or build with Gradle)
- `catalogue.json` — versioned book catalogue (schema v1). The app fetches this from the `main` branch.
- `.github/workflows/android-ci.yml` — builds debug APK and runs unit tests

## Owner workflow: add a new book (no APK rebuild)

1. Upload the PDF as a **Release asset** on this repository.
2. Compute SHA-256 of the file:
   ```bash
   sha256sum my-book.pdf
   ```
3. Add an entry to `catalogue.json` on `main`:

```json
{
  "id": "unique-book-id",
  "title": "Book Title",
  "authors": ["Author Name"],
  "description": "Optional description",
  "categoryIds": ["general"],
  "language": "en",
  "coverUrl": null,
  "releaseTag": "1.1.2",
  "assetName": "my-book.pdf",
  "downloadUrl": "https://github.com/OWNER/REPO/releases/download/TAG/my-book.pdf",
  "fileSize": 12345678,
  "sha256": "64-char-hex-sha256",
  "pageCount": null,
  "publishedAt": "2026-11-20",
  "featured": false,
  "tags": ["tag1"]
}
```

4. Commit & push `catalogue.json`. Users pull-to-refresh or wait for background sync — the new book appears without an APK update.

## Build & install

### GitHub Actions (phone-friendly)

1. Go to **Actions** → **Build CloudShelf Android APK** → **Run workflow**
2. Download the `CloudShelf-Debug-APK` artifact
3. Install the APK on the device (allow unknown sources if needed)

### Local

```bash
cd android-app
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Security model (summary)

- Offline PDFs are stored **only** in app-private `noBackupFilesDir`
- Encryption: **AES-256-GCM**, chunked streaming format `CSPDF1`
- Per-book DEK wrapped by a master key inside **Android Keystore**
- SHA-256 verified before encryption
- Catalogue URLs and PDF download URLs must be HTTPS and on an explicit host allowlist
- Online reading uses a **bounded temporary cache** (not permanent offline storage)
- Removing an offline copy never deletes reading progress, bookmarks, or annotations

**Limits:** Rooted devices / instrumentation / screenshots can still expose content while it is rendered. This is not DRM.

## PDF engine

Current implementation uses **Android PdfRenderer** with:

- Bounded temporary cache for online reading
- Decrypt-to-private-session-file for offline reading (file unlinked after open where possible)

Text selection / native PDF annotations are limited by `PdfRenderer`. App-side annotation overlays are designed for future extension.

## Architecture

Clean architecture + MVVM:

- `domain` — models, repository interfaces
- `data` — Room, remote catalogue, repository implementations
- `feature` — Compose screens + ViewModels
- `core` — security, PDF cache, config

## Tests

```bash
cd android-app
gradle testDebugUnitTest
```

Includes:

- Chunked encryption round-trip + tamper rejection
- Catalogue validation (unsafe URLs, duplicate IDs, schema version)

## Known limitations / next steps

- No real PDFs in release `1.1.2` yet — upload assets and fill `catalogue.json`
- Annotation drawing UI is present as foundation (`AnnotationCanvasOverlay`); full toolbars can be expanded
- Continuous vertical scroll mode can be added on top of page renderer
- Release signing secrets not configured in CI (debug APK only)

