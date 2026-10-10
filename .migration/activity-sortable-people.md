#### 🔴 Activity contributor kind

Custom clients of `GET /workspaces/{slug}/activity/people` and `GET /workspaces/{slug}/activity/people/{userId}` must read `kind` instead of `automation`.
`kind` is `PERSON`, `BOT` for a provider bot account, or `AUTOMATION` for an account that a workspace admin treats as automation.
A provider bot stays `BOT` even when an admin classified it.
The bundled webapp needs no operator action.
