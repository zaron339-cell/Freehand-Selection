# History and window implementation

User requirements: independently resize the AI window's width/height; move it beyond display edges with a recovery route; locally retain conversations and question images for continued follow-up; show the screen capture full-size under smaller floating controls, as supplied image p2.

Implementation: private atomic JSON conversation records plus private PNG question images; preserve completed turns, author/hidden metadata, pending interrupted reply, draft, selected profile and window geometry. Serial background storage with atomic image writes, no API keys in history, explicit deletion and no silent pruning. Individual atomic files replace the initially proposed SQLite index because this history has no relational queries; listing sorts metadata and paginates the UI. Resume does not automatically request AI again. History can resume without a new screenshot authorization using a user-initiated specialUse foreground overlay service; capture itself continues to use MediaProjection.

Window: independent right/bottom/corner grips, usable minimum dimensions, physical-screen maximum dimensions, unrestricted off-screen movement with LAYOUT_NO_LIMITS. Visible ball recovers a displaced answer; history opens recovered bounds. Close saves instead of clearing. Screen rotation preserves user dimensions when possible.

Selection: screenshot canvas occupies full overlay; compact 36dp controls float above it and may be dragged aside. Only cropped preview is fit between controls. Original pixel mapping remains unchanged.

- [x] Confirm three failing native UI regressions on old code; add storage/geometry round-trip checks and actual independent resizing UI checks.
- [x] Implement repository, answer restoration/autosave, history page, window controls/recovery and full-size selection.
- [x] 96 native checks and geometry pass. Local UI verifies process restart/restored follow-up, original image, no auto request, independent resizing and off-screen recovery. Screenshots inspected.
- [x] Independent review and follow-up fixed recovery z-order, same-ID stale restore, pending turn loss, preview bounds, and async restore ordering. Version 0.4.0 built and signature verified; README/VALIDATION updated.

No Git repository exists; use the already-authorized project workspace without Git-specific worktree/commit actions.
