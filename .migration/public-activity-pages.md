#### 🔴 Whole-workspace anonymous access is removed

Take and verify a database backup before this upgrade. Restore that backup if you must reverse the schema cutover.

The old public workspace flag no longer grants anonymous API access.
Workspace APIs now require sign-in and workspace access.
The replacement public activity page defaults to off for each workspace, including workspaces that used the old flag.

Set `HEPHAESTUS_PUBLIC_ACTIVITY_ENABLED=true` only when public activity pages are appropriate for this instance.
An instance administrator can override this default in the public activity settings API.
A workspace administrator must then enable each page explicitly.
Verify repaired activity history before publication and keep course workspaces private.
See the public activity administration guide for privacy controls, objections, and API paths.
