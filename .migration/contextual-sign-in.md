#### 🔴 Back up before the first-login notice is dropped from the database

This release stops storing the first-login notice. The upgrade drops the `consent_notice` table, its
immutability trigger and function, and the `consent_decision.notice_sha256` column, and rewrites
`enforce_consent_decision_append_only` so it no longer compares that column. Take the usual
pre-upgrade database backup: none of it can be rolled back.

Nothing you need is lost. Each recorded decision keeps the `notice_version` it was taken against, and
the archived `2026-08-30` wording and its SHA-256 digest remain reproducible from
`db/changelog/0000000000000_baseline_v0_77_4.sql`, which is part of every release and never changes.
From this release the wording lives in the first-login screen itself, so the version identifies a
published, signed release rather than a row.

The wording also changed, and its version moved to `2026-09-10`, so every existing account accepts it
once more at its next sign-in. The new screen carries no operator-specific text: it points to
`/imprint` and `/privacy` for who runs the deployment and what it stores. Configure both before the
upgrade, or the first thing your users read will point at the built-in placeholder — see
[Legal Pages](https://docs.hephaestus.build/admin/legal-pages).
