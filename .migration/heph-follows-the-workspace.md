#### 🔴 Heph follows the workspace setting, not per-account grants

Hephaestus no longer reads `mentor_access` rows in `account_feature`. Every member of a workspace
with **Chat with Heph** turned on can use Heph, in the web app and in Slack direct messages, subject
to their own AI choice. If you granted `mentor_access` to only some accounts to run a limited pilot,
turn off **Chat with Heph** under the workspace's **Administration → Settings** before upgrading, in
every workspace you are not ready to open to all of its members. Leftover `mentor_access` rows have
no effect and need no clean-up.
