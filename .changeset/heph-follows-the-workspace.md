---
"hephaestus": minor
---

A member who finishes workspace setup can now open Heph as soon as a workspace admin turns on **Chat
with Heph**. Before, the web app also needed a hidden per-account grant that only a database edit
could add, so members were turned away from Heph. Heph now works the same way on the web
and in Slack direct messages: it is available to every member of a workspace that has it on, and
each member's AI choice still decides whether it answers. Turning Heph off or on takes effect
without signing in again. A link to Heph in a workspace that has it off now says so instead of
silently returning to the workspace home. When Heph can't answer a member, it now gives the actual
reason on the web and in Slack alike: they chose No AI, they still have to make a required AI
choice, or no Heph model in the workspace is within their choice. Before, every one of these was
reported as Heph not being set up for their AI choice. In Slack, Heph no longer starts a
conversation or shows that it is reviewing their feedback before it declines. Instance admins can
still administer a workspace they are not a member of, but no longer get Heph there.

**Operators:** Hephaestus no longer reads per-account `mentor_access` grants. If you used them to
open Heph to only some members of a workspace, every member of that workspace can now use it. Before
upgrading, turn off **Chat with Heph** in any workspace you are not ready to open to all members.
