---
"hephaestus": minor
---

API clients: `GET /api/user/features` no longer returns `ADMIN`. To tell whether the signed-in account is an instance administrator, read `appRole` (`APP_ADMIN`) from `GET /api/user`, as the web app does. The response keeps `PAGE_EXPERIENCE_METRICS`.
