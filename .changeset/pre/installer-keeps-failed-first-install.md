---
"hephaestus": patch
---

When the first install of the one-host installer fails, its Jobs and Pods now stay for diagnosis, and the installer links to the troubleshooting steps. Before, Helm rolled the install back and removed them. Run the installer again with the same release to retry.
