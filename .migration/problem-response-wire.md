#### 🔴 Read problem responses from the top level

Before upgrading, update custom API clients to accept `application/problem+json` for error responses. Read `requestId`, `errors`, and other extension fields from the top level of the body. The generated model no longer declares a nested `properties` object.

CSRF denials use this problem response in both workspace address modes. On a 403 with type `https://docs.hephaestus.build/contributor/api-error-handling#csrf-token-rejected`, fetch a new CSRF token once, then retry the refused request once. Keep the existing handling for other 403 responses.

Regenerate clients from the OpenAPI specification.
