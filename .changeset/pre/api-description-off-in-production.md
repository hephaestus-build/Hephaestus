---
"hephaestus": minor
---

A production server no longer publishes its OpenAPI description or Swagger UI. It refuses to start when the description is turned on, and configuration readiness names the setting.

**Operators:** If you set `SPRINGDOC_API_DOCS_ENABLED` to anything other than `false` on a production server, remove it before you upgrade. It is off by default.
