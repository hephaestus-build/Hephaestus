#### 🔴 Update API clients that read error codes

This release reports every API error in one RFC 9457 problem model.
A client tells problems apart by the `type` member.
Stored data does not change.

Each `type` is an absolute address of the form `https://docs.hephaestus.build/contributor/api-error-handling#<slug>`.
[API error handling](https://docs.hephaestus.build/contributor/api-error-handling#problem-types) lists every slug.
A problem without its own kind keeps `about:blank`, so its HTTP status is its kind.

Before you upgrade, find each place where your API clients or scripts read an error body.
Replace each removed member in the first column with the member in the second column.

| Removed | Read instead |
| --- | --- |
| `code`, `reason`, `kind` or `reasonCode` that names the kind of error | `type` |
| `code` on a refused feedback approval | `type` `…#feedback-withheld` and `withholdingReason` |
| `error` on a failed OAuth callback | `type` `…#oauth-callback-failed` and `callbackError` |
| The relative `/problems/<slug>` types and `urn:hephaestus:csrf` | The absolute `type` with the same slug, and `…#csrf-token-rejected` |
| The `{ "error": "…" }` body | The problem body |
| `ownerUserId` in the request that creates a workspace | Nothing. The signed-in creator owns the workspace. |

The `/internal/**` sandbox endpoints are no longer in the published API description.
Only the sandboxes call them.
