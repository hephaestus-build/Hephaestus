---
"hephaestus": minor
---

Connecting a GitHub App installation to a workspace now confirms, through GitHub, that the person who installed the app owns the account it is installed on, and an installation can be connected to only one workspace at a time. If another workspace already holds it, the refusal names that workspace to its administrators. People who install the app from GitHub or from the workspace wizard land on the Hephaestus home page, and their workspace appears as before. **Operators:** turn on **Request user authorization (OAuth) during installation** on the GitHub App, set its callback URL to `https://<your host>/oauth/callback/github`, and set `GH_APP_CLIENT_ID` and `GH_APP_CLIENT_SECRET` to the App's client credentials.
