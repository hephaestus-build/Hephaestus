---
"hephaestus": patch
---

Shared HTTP error diagnostics no longer repeat full upstream URLs, SQL error text, or rejected row values; structured exception types, HTTP status, and valid SQLSTATE codes remain available for troubleshooting. GitLab webhook and Outline request diagnostics no longer include raw provider responses or transport error text. Startup records expose the enabled runtime roles as queryable fields. Credential masking also covers more common provider tokens and authentication fields without replacing source-level privacy controls.
