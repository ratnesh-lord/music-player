# Shared

Things both apps must agree on.

* `test-vectors/*.json` — inputs and expected outputs for track keys, ReplayGain decisions, quality
  labels, LRC parsing and sync merging. Run by `android/core` (`SharedVectorsTest.kt`) and
  `web/tests/shared-vectors.test.ts`; a change in one implementation that isn't mirrored fails CI.
* `sync-schema.md` — synced records, merge rule, Supabase REST calls, backup file format.
* `design-tokens.json` — colours, fonts, sizes used by both UIs.
