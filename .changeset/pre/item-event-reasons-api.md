---
"hephaestus": patch
---

**API:** item event reasons have three new values: `QUIET_WINDOW`, `REVIEW_IN_PROGRESS` and `ALREADY_CURRENT`. They appear in the item events, item trace, review request and review responses. A client that switches exhaustively over these reasons must handle them.
