---
"hephaestus": patch
---

The GitLab workspace wizard now waits until it knows your server's GitLab instance and creates the
workspace there, instead of briefly showing gitlab.com and validating your token against the wrong
server. GitLab workspaces are created only on the instance Hephaestus syncs from
(`GITLAB_DEFAULT_SERVER_URL`); any other instance is refused before your token is sent or stored.
The new workspace belongs to your GitLab account on that instance, so link it first. When GitLab
refuses your token or does not return your groups, the wizard says so instead of showing an empty
list. A GitHub workspace you create now belongs to your GitHub account, even if you linked GitLab
first.

The workspace connections API (`POST /workspaces/{slug}/connections`) no longer adds GitLab
connections; GitLab is connected by creating a GitLab workspace.
