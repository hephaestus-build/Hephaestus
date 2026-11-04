---
"hephaestus": patch
---

When GitLab refuses a workspace's token, that workspace's sync now pauses until you replace the token. Before, every sync kept calling GitLab and logged an error for each project. The server logs the refusal once, and sync resumes by itself after you reconnect.
