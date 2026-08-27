# MonOCR Android — Architecture Guide

A quick-start reference for new engineers and long-term maintainability.

---

## Layers

```
dev.janakhpon.monocr/
├── ui/                   <- Compose screens + components + ViewModels
│   ├── screens/          <- One file per screen (HomeScreen, ResultScreen, ...)
│   ├── components/       <- Shared composables (HistorySection, PdfPreviewList, ...)
│   ├── theme/            <- MaterialTheme wiring (colors, typography)
│   ├── MainViewModel.kt  <- Top-level OCR state: Initializing -> Ready -> Processing -> Success
│   ├── ContributeViewModel.kt <- Isolated state for Contribute screen
│   └── FeedbackViewModel.kt   <- Isolated state for Feedback screen
│
├── engine/               <- Pure OCR pipeline (no Android UI deps)
│   ├── OcrRepository.kt  <- Orchestrator: delegates to engine, saves history
│   ├── MonOcrEngine.kt   <- ONNX Runtime inference session
│   ├── LineSegmenter.kt  <- Horizontal projection -> line bounding boxes
│   ├── ImagePreprocessor.kt <- Bitmap -> [1,1,160,1024] float tensor
│   └── CtcDecoder.kt     <- Greedy CTC decoding of logits
│
├── data/                 <- Room persistence layer
│   ├── HistoryRecord.kt  <- @Entity: one record per scan/contribution/feedback
│   ├── HistoryDao.kt     <- Reactive queries via Flow
│   └── HistoryDatabase.kt <- Singleton Room DB with versioned migrations
│
└── util/                 <- Stateless helpers
    ├── MonLogger.kt      <- Thin wrapper around Log (+ future Sentry hook)
    ├── PdfUtil.kt        <- PdfRenderer helpers (coroutine-safe)
    └── FileUtil.kt       <- Content URI -> file name helpers
```

---

## Data Flow

```
User Action
    │
    ▼
Screen (Composable)
    │  calls
    ▼
ViewModel (state owner)
    │  calls suspend fun
    ▼
OcrRepository
    │  ┌─────────────────────┐
    │  │  MonOcrEngine       │  ← ONNX inference
    │  │  LineSegmenter      │  ← image segmentation
    │  │  ImagePreprocessor  │  ← tensor prep
    │  └─────────────────────┘
    │  saves to
    ▼
HistoryDao (Room / Flow)
    │  observed by
    ▼
ViewModel StateFlow → Screen recompose
```

---

## Key Design Decisions

| Decision | Reason |
|----------|--------|
| Manual DI (no Hilt) | Keeps entry points explicit; appropriate for small team |
| ONNX Runtime (not TFLite) | Better Mon language model compatibility |
| Room + Flow | Reactive history without polling |
| Per-feature ViewModels | Low coupling; each screen owns its own state lifecycle |
| `object` for stateless engine steps | `LineSegmenter`, `ImagePreprocessor`, `CtcDecoder` are pure functions — easy to unit test |

---

## Database Migrations

Migrations live in `HistoryDatabase.kt` as named constants (e.g., `MIGRATION_1_2`).

> **Rule**: Never use `fallbackToDestructiveMigration()` in production — it silently wipes user history on schema changes.

When adding a new column:
1. Bump `version` in `@Database`
2. Add `MIGRATION_X_Y` constant
3. Register it with `.addMigrations(...)`

---

## Testing

| Layer | Test Type | Location |
|-------|-----------|----------|
| `CtcDecoder` | Unit | `src/test/.../engine/CtcDecoderTest.kt` |
| `LineSegmenter` | Unit | `src/test/.../engine/LineSegmenterTest.kt` |
| ViewModels | Unit (TODO) | Use `TestCoroutineDispatcher` + in-memory Room |
| Screens | UI (TODO) | Compose test rules |

---

## Observability

All errors pass through `MonLogger.e()`. When releasing to production, uncomment and configure `Sentry.captureException` inside that method. No other changes needed.
