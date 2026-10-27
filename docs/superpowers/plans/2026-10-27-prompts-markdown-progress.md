# Ledger — plan: 2026-10-27-prompts-markdown.md

- Task 1 complete: native UI/network tests reproduced 11 presentation failures on unmodified 0.2.0 (default constraints, missing entry, hidden-prompt leakage, raw Markdown, absent bold, ignored custom prompts, generic/reassigned names).
- Task 2 implementation: global prompt settings and three protocol adapters, native Markwon rendering, presentation-only turn metadata. Initial expanded suite passed 76 checks; a strengthened check then reproduced duplicated span-instance loss in identical answers.
- Duplicate-response regression RED: `identical answers lost one message's bold spans`. Fix: cache rendered spans by turn position rather than equal message value. GREEN: full native suite 76 checks passed.
- Independent review found one P2: the first list marker could disappear when Markdown was appended immediately after the author colon. Both completed and streamed cases reproduced RED. Fix puts the author on its own paragraph; full GREEN suite: 78 checks passed.
- Task 2 complete: final build, geometry and lint succeeded (0 errors / 17 warnings); final APK installed and all 78 native checks passed. No deferred review minors.
- Task 3 complete: prompt UI persistence/reset, full local-fixture conversation and floating-window smoke tests passed; screenshots inspected. Signing matches 0.2.0, version 0.3.0 copied to dist, README/VALIDATION updated. No open material review findings.
- Prompt editor UI: save/reopen and restore-defaults passed on the dedicated emulator. The initial script input race was fixed by clearing/waiting before injecting text; stored values and direct native tests confirmed no product persistence defect.
- Workspace decision: this user-specified project has no Git repository. No worktree/commit operations apply; artifacts and validation records remain in the current workspace.
- Defaults, global prompt scope and renderer choice follow the design document; no additional product scope added.
