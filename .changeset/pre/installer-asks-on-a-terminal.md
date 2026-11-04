---
"hephaestus": minor
---

On a terminal, the one-host installer now asks for each value that a first install lacks. It asks for the release, the host name, the e-mail address, the GitHub OAuth app and its client secret, and your GitHub login. It prints the two URLs to enter at GitHub, does not show the secret, and puts your numeric GitHub user ID in the admin list. A run without a terminal asks nothing. `--render-site` prints the `site.yaml` that a first install would write.

The installer no longer writes or keeps a `site.yaml` without a login provider: such an instance starts, but nobody can sign in to it.

**Operators:** a scripted first install now needs `--github-client-id` and `GITHUB_OAUTH_CLIENT_SECRET`. A repeat run stops if your `site.yaml` names no GitHub or GitLab login provider. The migration guide gives the fix.
