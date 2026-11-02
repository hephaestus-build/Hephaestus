#### 🔴 Give the one-host installer a login provider

The one-host installer no longer writes or keeps a `site.yaml` without a login provider.
Such an instance starts, but nobody can sign in to it.

- For a scripted first install, give `--github-client-id` and set `GITHUB_OAUTH_CLIENT_SECRET`.
  On a terminal, the installer asks for both.
- Before a repeat run, make sure that `site.yaml` names a login provider.
  Set `config.github.oauthClientId`, or enable GitLab with `config.gitlab.enabled: true` and `config.gitlab.oauthClientId`.
  [First steps § 1](https://docs.hephaestus.build/admin/first-steps#1-configure-a-login-provider) describes the GitHub OAuth app.
