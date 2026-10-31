---
"hephaestus": patch
---

Person erasure now removes references that it used to leave behind.
It clears the person as the author of practice versions and keeps the practice definitions.
Before, the database refused that change.
It deletes the rate-limit buckets of the person's account and keeps IP address buckets.

The upgrade changes the database schema in two ways, with no operator action.
It drops the unused author columns of model prices and model grants.
It replaces the database function that guards practice versions, so that it accepts a cleared author.

Person exports now list the practice versions that the person saved and the person's rate-limit buckets.
They also list the workspaces that are installed on the person's own GitHub or GitLab account.
The login of such an installation stays as the workspace's name.

The server and each worker now remove their own Git mirrors of a repository that a workspace removes.
They also remove all mirrors of a purged workspace.
Before, workers kept them.
The server kept a workspace's mirror while another workspace monitored the same repository.
