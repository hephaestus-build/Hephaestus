# Runbooks

One-off operational guides for a migration or cutover that the team ran once: what had to be true
before, the order of operations, and how to roll back.

| Runbook | What it covers |
| --- | --- |
| [`auth-cutover.md`](auth-cutover.md) | Shipping the Keycloak → Spring-native auth replacement (ADR 0017): pre-launch checklist, first-instance-admin bootstrap, rollback. |

**Repo-only, like `docs/decisions/`.** Nothing in this directory is registered with a Docusaurus
plugin, so none of it appears on [the site](https://docs.hephaestus.build/). Link a runbook
from a published page by absolute GitHub URL
(`https://github.com/hephaestus-build/Hephaestus/blob/main/docs/runbooks/<file>.md`), never by a relative
path — see [`../decisions/README.md`](../decisions/README.md) for the full rule.

**Standing operations do not live here.** A step a self-hoster performs on an ordinary install
belongs in the Admin Guide (`docs/admin/`), which is published; a runbook may point at it, not
replace it. First-instance-admin bootstrap, for example, is documented for operators in
`docs/admin/install.mdx` — the runbook section is the cutover-time detail behind it.
