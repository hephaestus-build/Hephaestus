---
"hephaestus": minor
---

When someone grants or removes the instance-admin role, or the admin or owner role in a workspace, Hephaestus now sends an email to the affected person. The other instance admins, or the other owners of the workspace, also get an email. The email names who made the change, when, and where to review it.

Roles read from the first member list of each GitHub or GitLab connection send no email, including changes for people already in the workspace. A replacement connection starts with a silent first import too. Later grants and role changes send an email and name the sync.

An elevation that you make to your own account also sends an email; lowering your own role sends none. A member who joins or leaves without the admin or owner role sends none. Only the first bootstrap administrator of a fresh instance is silent. The general email "Your instance admin access changed" is no longer sent.

The upgrade closes queued copies of the previous general notice and keeps completed email receipts. A send whose outcome is unknown is not repeated.

Security notices addressed to other people remain after an account is erased, with the erased identity removed.

**Operators:** Install this change in the stopped window before the 1.0.0 cut. Stop every runtime role before the migration, then start only the upgraded roles. Use the stopped upgrade procedure. Do not run old and new servers together during this change.
