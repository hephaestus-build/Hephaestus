---
"hephaestus": minor
---

An exemption on a whole class of code no longer turns off the server's check that a database statement stays inside one workspace. Only an exemption on one method does. A statement that the check refuses is counted in `tenancy_violation_total` and logged as `Tenancy violation (throw)`. The new alert `HephaestusTenancyViolations` fires on it. Load the rule file of the release to get it. The `tenancy_bypassed_violation_total` metric is removed. It counted what the class-wide exemptions let through. Its bundled dashboard panel is removed, and your own panel that reads it shows no data now.
