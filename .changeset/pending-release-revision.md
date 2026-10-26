---
"hephaestus": patch
---

A chart host whose upgrade was cut off now rolls back to the newest revision that Helm finished, not to a failed one. When Helm never finished a revision, as after a cut-off first install, the update fails and names the `helm uninstall` command that clears the release; the next update installs it again.
