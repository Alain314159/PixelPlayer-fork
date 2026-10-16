# PixelPlayer-fork — Pending AI-Related Work

This file lists all AI-related tasks that have been deferred per user request
("déjalo para después"). Non-AI work has been completed in the current branch
`refactor/architecture-cleanup-and-bugfixes`.

## Status

| # | Task | Effort | Status |
|---|---|---|---|
| 1 | Migrate `GeminiAiClient.kt` to official `com.google.genai:google-genai:1.78.0` SDK | M | Pending — SDK dependency wired, code migration deferred |
| 2 | Replace `GeminiAiClient.getDefaultModels()` hardcoded list with live `/models` endpoint query | S | Pending — will come for free with #1 |
| 3 | Migrate `GeminiModelService.kt` from `HttpURLConnection` to OkHttp (consistency) | S | Pending |
| 4 | Add streaming support for AI playlist generation (SSE) | L | Pending — needs protocol rework in `AiPlaylistGenerator` |
| 5 | Add multimodal input support (image → playlist based on album art mood) | XL | Pending — requires UI for image picker |
| 6 | Implement AI function-calling for advanced playlist queries ("play 5 songs from the 90s under 3 minutes") | L | Pending |
| 7 | Add AI usage telemetry dashboard (per-provider token count, daily/weekly charts) | M | Pending — `AiUsageDao` already exists, just needs UI |
| 8 | Migrate `AiHandler.providerCooldowns` from ConcurrentHashMap to proper rate-limiter library (Resilience4j / Bucket4j) | S | Pending |
| 9 | Add prompt template versioning — currently prompts are hardcoded in `AiPlaylistGenerator` | M | Pending |
| 10 | Implement AI-powered smart shuffle ("play songs that go well with the current track") | L | Pending — needs embedding-based similarity |

## Completed AI Work (in this branch)

The following AI bugs were already fixed in earlier commits of this branch:

- ✅ **Bug I1 (HIGH)**: `GeminiAiClient.DEFAULT_GEMINI_MODEL` was `"gemini-3.1-flash-lite"` (non-existent) → now `"gemini-2.5-flash"`. Also updated `getDefaultModels()` list to real current models.
- ✅ **Bug I2 (HIGH)**: `GenericOpenAiClient.getAvailableModels` and `validateApiKey` leaked OkHttp connections on the error path → wrapped in `.use {}`.
- ✅ **Bug I3 (HIGH)**: `AiPlaylistGenerator` JSON-escaped only `"` → added `escapeJsonString()` for proper JSON escaping + `escapeXml()` for prompt-injection protection on user queries.
- ✅ **Bug I4 (MEDIUM)**: `AiHandler.providerCooldowns` was a `mutableMapOf` (not thread-safe) → migrated to `ConcurrentHashMap`.
- ✅ **Bug I5 (HIGH)**: `AiProviderSupport.buildProviderChain` iterated 10 fallback providers always → pre-filtered to only providers with non-blank API keys.
- ✅ **Bug I6 (HIGH)**: `com.google.genai:google-genai:1.58.0` was declared but unused → bumped to 1.78.0 and wired into `app/build.gradle.kts` dependencies.
- ✅ **Bug I7 (LOW)**: Cache hit in `AiHandler.generateContent` didn't record AI usage → documented as intentional (no tokens consumed).

## Why AI work was deferred

The user explicitly requested: "anota todo lo que solo esté relacionado con IA y dejémoslo para después".
The AI features are working correctly after the bug fixes above; the remaining
items are enhancements (streaming, multimodal, function-calling) that can wait
until the core app is stable.
