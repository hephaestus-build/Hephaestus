---
"hephaestus": minor
---

Operators can publish Hephaestus in numbers: the totals of their instance for each closed half-year, at `GET /api/public/usage`, with no sign-in. It shows workspaces, developers whose work was reviewed, reviewed work, practice reviews, and practice feedback delivered. Each figure is rounded down, and a half-year shows figures only if at least 5 workspaces and 5 developers took part. It is off by default. Set `config.publicUsage.enabled`, or turn it on with `PUT /api/admin/settings/public-usage`. The first release covers every closed half-year since the first practice review. An instance admin can withdraw a released half-year or one of its figures for good. The reads have their own rate limit, 60 each minute by default (`HEPHAESTUS_RATE_LIMIT_PUBLIC_USAGE_CAPACITY`, `HEPHAESTUS_RATE_LIMIT_PUBLIC_USAGE_PERIOD`).
