#### 🔴 Heph follows AI models; the Chat with Heph switch is removed

The workspace switch **Chat with Heph** is removed. Heph is offered to every member of a workspace
where a Heph model is ready under **Administration → AI models**, in the web app and in Slack direct
messages, and each member's AI choice still applies.

**Before upgrading**, turn off **Chat with Heph** under **Administration → Settings** in every
workspace that should not offer Heph. The upgrade carries the switch over: in each workspace where it
is off, it turns off the Heph model rows, and a workspace admin turns them back on under **AI models**.
Which rows were on before is not recorded, so undoing this needs a pre-upgrade backup.

The [restore-clone lockdown](https://docs.hephaestus.build/admin/backup-restore) now turns off every
Heph model row instead of the removed switch; turn them back on under **AI models** after lifting the
lockdown.
