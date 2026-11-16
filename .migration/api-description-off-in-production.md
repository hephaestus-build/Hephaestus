#### 🔴 Turn off the OpenAPI description on a production server

- **When:** before the window
- **Action:** On a production server, remove `SPRINGDOC_API_DOCS_ENABLED` if it is not `false`.

A production server now refuses to start unless `SPRINGDOC_API_DOCS_ENABLED` is `false`. Configuration readiness reports the fact `api.description`.

The setting is off by default, and the chart does not set it. Swagger UI needs the OpenAPI description, so it is off with it. The [OpenAPI specification](https://github.com/hephaestus-build/Hephaestus/blob/main/server/openapi.yaml) in the repository describes the API.

This change has no schema change, so old and new servers can run together.
