---
"hephaestus": minor
---

Instance admins can switch a workspace's GitLab group webhook to a GitLab 19.1+ signing token, and back to the secret token, through the connections API when `WEBHOOK_SECRET` is a `whsec_` signing token. Each switch keeps a working token on the webhook at every step and reports success only once GitLab shows the webhook in its new state. GitLab then stops sending the secret in plain text for that workspace; Hephaestus still accepts deliveries that carry it. A signed GitLab webhook is now accepted only when its signing token encodes the 32-byte key GitLab requires.
