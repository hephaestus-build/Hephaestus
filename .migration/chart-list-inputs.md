#### 🔴 Convert chart list values to arrays

Before upgrading, change these values in your site file to YAML arrays:

- `config.auth.bootstrapAdmins`
- `config.auth.browserExtensionIds`
- `config.auth.passkeys.allowedOrigins`
- `config.outline.allowedOrigins`

Use `[]` in place of an empty string. Split a comma-separated string into one array item for each value, in the same order:

```yaml
config:
  auth:
    bootstrapAdmins:
      - "github:1234567"
      - "gitlab:@example"
    browserExtensionIds: []
    passkeys:
      allowedOrigins: []
  outline:
    allowedOrigins: []
```

An array item cannot contain a comma. The chart rejects the old string form.
