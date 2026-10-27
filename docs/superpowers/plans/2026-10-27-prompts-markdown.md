# Prompts and Markdown Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Steps use checkbox syntax for tracking.

**Goal:** Deliver the four requested prompt and response presentation changes in an upgrade APK.

**Architecture:** Separate prompt persistence, API context and conversation presentation. Render response Markdown through native Markwon spans; keep request text and per-turn provider identity intact.

**Tech Stack:** Kotlin, Android Views, SharedPreferences, Markwon 4.6.2, existing three protocol adapters.

**Spec:** ../specs/2026-10-27-prompts-markdown-design.md

## Global Constraints

- Android API 26 minimum; preserve existing model profiles and Keystore keys.
- No prescribed output format, academic field, formula, or mandatory step sequence in defaults.
- Hide automatic initial questions in UI only; retain actual manual questions and full network context.
- Preserve response-time model names across provider switches; format streamed and complete answers alike.
- Render Markdown natively without fetching remote images or executing HTML.

## Review Focus

- Old installations have no prompt keys: use new defaults without erasing API configurations.
- Blank system prompt: omit the system field in all three protocols; blank action prompts fall back to short defaults.
- Retry/cancel: do not expose or duplicate the hidden automatic question.
- Provider switching: old responses keep their original author label.
- Markdown: preserve code literals and escaped syntax; do not silently load remote images or open unsafe URL schemes.

### Task 1: Reproduce current behavior

- [x] Add native UI/network regression tests for editable action prompts, custom system prompt, Markdown spans, hidden initial question, and response-time names.
- [x] Build/install/run the tests against the current app. Expected: assertion failures in these behaviors, not compilation errors.

### Task 2: Implement prompts and presentation

Files: create `ai/AiPromptStore.kt`, `AiPromptSettingsView.kt`, `AiPromptSettingsActivity.kt`, `ConversationRenderer.kt`; modify `AiWire.kt`, `AiClient.kt`, `AiConversationView.kt`, `SelectionOverlay.kt`, `MainActivity.kt`, manifest and app Gradle.

- [x] Add global prompt load/save/reset with the exact defaults in the spec and entry points in home/preview/answer.
- [x] Supply the chosen system prompt to all protocol request bodies and use configurable button prompts.
- [x] Store hidden-question and assistant-name presentation metadata separately from wire role/text.
- [x] Use Markwon for assistant Markdown with readable bold black emphasis; retain manual questions and literal code.
- [x] Run regression and existing checks. Expected: all pass, lint has zero errors.

### Task 3: Verify and package

- [x] Use the local test server for settings persistence, formatted streaming response, follow-up, clipboard, and a movable overlay; inspect screenshots.
- [x] Request an independent final review and fix material findings with reproductions.
- [x] Build and check the final APK, verify signing compatibility, update README/VALIDATION and copy version 0.3.0 to dist.

## Execution notes

Native execution follows the user's explicit implementation request. No Git repository is present, so Git-specific worktree, commit and merge steps do not apply. Evidence is retained in build/test outputs and VALIDATION.md.
