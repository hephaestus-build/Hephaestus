---
"hephaestus": minor
---

`hephaestus-keys.sh --export-keys FILE` now backs up the keys that a restore needs to a new file that only you can read, and `--check-keys FILE` proves that a kept copy still matches the release Secret. Both print key names, never a value, and change nothing in the cluster. The export refuses to overwrite a file and refuses a Secret without its encryption keys. The one-host installer now ends with the export command.
