#### 🔴 Name your research organisation, and check your legal pages, before upgrading

First-login setup is now one short screen with no operator-specific text. It states the terms of use
and points at `/imprint` and `/privacy` for who runs the deployment, what it stores and for how long.
Configure both before you upgrade — on an instance still serving the built-in placeholder, the first
thing a new account reads now points at nothing.

**The research question is no longer asked by default.** It appears only where
`HEPHAESTUS_RESEARCH_ORGANIZATION` names the organisation that runs the study, which the screen and
the account-settings switch then show beside the choice; consent has to identify its controller. Set
it if you run a study. Leave it unset and setup is the terms alone, the settings switch is hidden, and
`PUT /user/consent/research` answers 404 — accounts that already answered keep their recorded
decision, and nothing is deleted.

The wording changed and its version moved to `2026-09-10`, so every account accepts it once more. The
gate runs on requests from existing sessions too, so signed-in users meet it as soon as the deployment
finishes rather than at their next sign-in.

Nothing is dropped from the database this release. `consent_notice` and its archived `2026-08-30`
wording stay exactly as the baseline seeded them, and `consent_decision.notice_sha256` only loses its
`NOT NULL`: decisions recorded from here on identify their wording by `notice_version`, which points
at the release that published it. A replica still running the previous image keeps working against
this schema, and rolling the image back stays possible. A later release removes both.
