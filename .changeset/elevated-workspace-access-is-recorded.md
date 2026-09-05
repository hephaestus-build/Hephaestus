---
"hephaestus": minor
---

Both audit viewers now show when an instance admin acted in a workspace they are not a member of. Instance admins have always been able to open any active workspace with admin rights without joining it; that access was simply invisible on the audit trails. Each such access window is now recorded as a "Workspace reached as instance admin" event in the sign-in audit log, and every settings change made that way is marked "Elevated" in the settings audit log — in the instance-wide console and in each workspace's own, so a workspace admin can see it too. The CSV export of the sign-in audit log gains a final `elevated_via_instance_admin` column; it is appended after the existing columns, so a spreadsheet or script that reads the export by column position keeps working. Events recorded before this release are unmarked, which means "no elevation recorded" rather than "the person was a member".
