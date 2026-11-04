---
"hephaestus": patch
---

Model calls that wait for a busy model hold the shared call limit's lock for less time. Each worker's admission round no longer rewrites every waiting call four times a second, because the regular lease renewal already keeps waiting calls alive.
