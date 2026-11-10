---
"hephaestus": patch
---

The GitHub App setup page now says when an instance admin declined an installation, instead of saying that it still waits for approval, and it names an existing workspace only to its members and to instance admins. Deleting the workspace of an adopted installation on a personal account now also removes that account's login and GitHub id from the installation record, and erasing the person does the same. A person's data export lists their adopted installations. An uninstall that arrives while an admin adopts the installation now removes the new workspace as well. A refused workspace owner change in the audit log now names its workspace.

**Operators:** stop every server for this upgrade, as the migration guide describes.
