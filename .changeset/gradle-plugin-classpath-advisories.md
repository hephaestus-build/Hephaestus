---
"hephaestus": patch
---

The build no longer resolves a vulnerable `graphql-java` or `handlebars` while generating provider clients. Neither reached a running Hephaestus — they are used only to generate code at build time — so no deployment is affected.
