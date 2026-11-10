---
"hephaestus": minor
---

A review attempt's sandbox can now reach any server replica. A server that did not capture the attempt rebuilds its evidence from PostgreSQL and its own Git mirror, and checks every checkout against the recorded commit, tree and digest before it uses it. A checkout that cannot be rebuilt ends the attempt as a capture failure, never as missing evidence. Person erasure and workspace purge no longer wait for the server that holds a copy, so a server that a rolling update replaced no longer blocks an erasure. Each running server removes erased evidence within 2 minutes.

Two new alerts watch this, with no action needed to install them: `HephaestusEvidenceReconcileStalled` and `HephaestusCheckoutUnverified`. The production operations runbook gives their response. The Git cache volume now also holds the checkouts that a server builds for other servers' captures.

Practice programs keep their executable permissions when another server rebuilds a review’s inputs.
