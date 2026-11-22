# Local Memory and Screenshot Answer Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Confirm a screenshot with an optional request, answer automatically, remember user preferences locally, and continue long conversations.
**Architecture:** Reuse Kotlin Views, existing protocol adapter and atomic conversation files. Add a bounded local memory store and text-only background extraction; store conversation summaries separately from the complete transcript.
**Tech Stack:** Kotlin / Android framework / org.json / existing Gradle and instrumentation runner.
**Spec:** `docs/superpowers/specs/2026-11-11-local-memory-auto-answer-design.md`

## Global Constraints

- Persist only on device; required text is sent to the configured API for extraction and answers.
- User confirms the selection before sending; optional instructions override automatic task selection.
- Preserve complete history, custom system prompts and all three API protocols.
- Deleted memories must not return from late background tasks.

## Review Focus

- Repeated confirmation and settings cancellation must not send duplicate requests.
- Screenshot text and assistant output must not become personal preferences.
- Clear/delete/edit while extraction is running must invalidate stale writes.
- Long conversation recovery must preserve partial replies and drafts.
- Keyboard insets must not change screenshot coordinate mapping.

### Task 1: Confirm and optional request

Files: SelectionOverlay, SelectionCanvas, AiPromptStore, AiPromptSettingsView, CaptureService, MainActivity, AiConversationView; tests: AiPresentationChecks.
- [x] Update UI tests: selection alone sends nothing; confirmation sends once; explicit request shown, fallback hidden; keyboard keeps canvas mapping stable.
- [x] Run instrumentation/build for baseline failure.
- [x] Replace two actions with confirmation and optional text; pass explicit/hidden flag, unify prompt editor, preserve custom system and legacy fields.
- [x] Build and run checks.

### Task 2: Local memory and text-only API

Files: ai/AiWire, ai/AiClient, ai/UserMemoryStore (new), ai/MemoryCoordinator (new), MemorySettingsView (new), MainActivity and AiConversationView. Tests: MemoryChecks (new), AiIntegrationChecks.
Interfaces: UserMemoryStore.snapshot/apply/select/edit/delete/clear, MemoryCoordinator.learn, AiClient.askText.
- [x] Add regression checks for bounded relevant memory, late writes, deleted source, protocol text-only requests and injected references.
- [x] Run failing checks, implement store and text-only transport, then run green checks.
- [x] Extract validated structured candidates from new user turns, tracking per-conversation cursors, evidence and revision; no extraction from screenshots or assistant text. Trigger at three turns, explicit preference or close.
- [x] Add management UI and independent extraction/use controls, shared across profiles. Verify failures do not affect answers.

### Task 3: Conversation compaction

Files: ConversationStore, AiConversationView, ai/ConversationContext (new); tests: MemoryChecks/HistoryChecks.
Interfaces: ConversationRecord.summary/summaryThrough; context compaction supplies text-only summary followed by recent complete pairs.
- [x] Test persistence compatibility, message coverage, failure recovery, partial turns, bounds and restart.
- [x] Implement 24-message/24000-character trigger, latest eight messages and summary <=3000 chars; keep original transcript.
- [x] Verify stop/retry do not advance cursors twice; background memory cannot resurrect deleted conversations.

### Task 4: Validation and delivery

- [x] Run geometry, instrumentation, assembleDebug, assembleDebugAndroidTest and lintDebug.
- [x] Fresh whole-change review; fix substantive findings and rerun affected checks.
- [x] Update README/VALIDATION and produce versioned APK in dist; report actual coverage and real-provider limits.

## Execution ledger

- Authorized implementation by user “做吧”; proceed inline without another plan approval.
- Branch isolation: `codex/local-memory-auto-answer`; worktree not needed for this single workspace. Initial sandbox branch write was denied; approved retry succeeded.
- No automatic commits or merge; leave reviewable working changes in the current branch.
- Tasks 1–4 complete: build, lint (0 errors / 30 warnings), geometry, 130 Android checks and real-IME UI smoke passed. APK and signature verified; see VALIDATION.md.
- Ruling: only generic preferences are used for an image without textual scope clues; later requests match the question plus recent context. This avoids applying English-specific habits to unrelated images without an additional image-classification request.
- Ruling: UI resizes for the IME while canvas measurement stays fixed. Initial ADJUST_NOTHING attempt visually hid controls despite a weak UI assertion; the check now reads the actual IME touchable region and asserts both input and confirmation above it.
- Review: four substantive findings fixed and covered by checks. Follow-up review found no new blocking issue; same-width real window-height changes remain a documented untested boundary.
