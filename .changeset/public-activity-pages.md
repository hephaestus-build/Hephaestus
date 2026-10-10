---
"hephaestus": minor
---

You can publish human contributions to public repositories with separate instance and workspace controls. Public pages default to off on self-hosted instances. People can hide their activity across all public pages, and workspace administrators can honor objections from contributors without an account. Public-page objections remain in force after account deletion or identity disconnection. Anonymous request limits can be adjusted for shared networks. Public pages exclude repositories when access is lost or a successful repository metadata confirmation is more than 48 hours old by default. You can set the visibility limit to match your sync cadence.

**Operators:** Whole-workspace anonymous access is removed. Take and verify a database backup before upgrade. Public activity pages must be enabled explicitly after the activity history repair is verified. The optional `HEPHAESTUS_PUBLIC_ACTIVITY_ENABLED=true` setting supplies an instance default. Review the public activity administration guide before publication.
