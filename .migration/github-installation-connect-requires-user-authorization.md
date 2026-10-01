#### 🔴 GitHub App: request user authorization during installation

Connecting a GitHub App installation to a workspace now needs the App's own client credentials, and
the App must request user authorization during installation. Before you upgrade, open the App's
settings on GitHub and do the following:

1. Under **Identifying and authorizing users**, turn on **Request user authorization (OAuth) during
   installation**. Set the **Callback URL** to `https://<APP_HOSTNAME>/oauth/callback/github`.
2. Under **General**, copy the **Client ID** and generate a **client secret**. Set them as
   `GH_APP_CLIENT_ID` and `GH_APP_CLIENT_SECRET` in `.env`.

Workspaces that are already connected keep working. The upgrade disconnects any GitHub App
connection that never recorded its installation, because such a connection could not run. It also
keeps each installation in the first workspace that connected it. Connections the upgrade
disconnects are recorded in their connection history. See
[GitHub integration](https://docs.hephaestus.build/admin/github-integration#connecting-an-installation-to-a-workspace).
