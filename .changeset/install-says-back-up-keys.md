---
"hephaestus": patch
---

The one-host installer now ends with a plain warning and the command that backs up your encryption keys. Hephaestus cannot make these keys again: without them, sign-in stops and the stored provider secrets, AI keys and integration credentials are lost. The command copies the keys from the release Secret into a file that only you can read, and prints no key. The admin docs give the same command for every install, on the Secret custody page.
