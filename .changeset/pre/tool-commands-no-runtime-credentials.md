---
"hephaestus": patch
---

Commands that Hephaestus runs for its tools in a review or a conversation no longer inherit the runtime's credentials: they see only the shell's basic settings and Git's settings. Its file tools open only the sandbox's workspace, and text that a sandbox sends to be stored or published has credential-shaped strings replaced.
