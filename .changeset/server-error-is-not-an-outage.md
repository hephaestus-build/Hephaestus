---
"hephaestus": patch
---

A review whose results the server refuses with its own error now ends at once with the practices it already finished, and the other practices end with `EXECUTION_FAILED`. Before, the error read as an unreachable server, and the review ran again until its retries were spent. The new `HephaestusReviewServerError` alert fires on the first such error, so report it as a defect.
