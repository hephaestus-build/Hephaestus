---
"hephaestus": patch
---

Database migrations no longer fetch Liquibase's analytics settings from liquibase.com or send Liquibase usage analytics. Set `LIQUIBASE_ANALYTICS_ENABLED=true` to send them again.
