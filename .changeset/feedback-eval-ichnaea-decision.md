---
---

Contributor CI only: a maintainer can now have Ichnaea decide a change to the feedback composer against main. A manual run, or adding `eval:decide` to a pull request from this repository, composes the 72 cases of the composition set with both composers, one replicate after another, and Ichnaea gives PASS, REGRESSION, INCONCLUSIVE, estimate-only or INVALID on these 72 cases, beyond run-to-run noise. A REGRESSION fails the check, which is never a required check: a person starts every evaluation. The composition set grows from 60 to 72 cases. Ichnaea is a development dependency of the repository root, pinned to a verified build commit. No shipped behavior changes and no operator action is needed.
