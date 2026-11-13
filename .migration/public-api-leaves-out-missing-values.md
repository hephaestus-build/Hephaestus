#### 🔴 Treat an absent field as missing in public API responses

Before upgrading, update custom API clients of the public operations. A response now leaves out a property that has no value. It no longer sends the property with `null`. This applies, for example, to `item` in delivered feedback, the optional fields of item references (`title`, `url`, `path`, `parentId`, `removedAt`), `displayName` and `avatarUrl` of accounts, and the timestamps of reviews and scans.

Treat an absent property and `null` the same, because an older server still sends `null`. In TypeScript, compare with `== null`, not `=== null` or `=== undefined`.

Regenerate clients from the OpenAPI specification.
