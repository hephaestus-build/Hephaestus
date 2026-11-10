#### 🔴 Move the GitHub webhook byte bound into the stream map

Before upgrading, replace `webhook.stream.maxBytesGithub` with this map in the site file, keeping the current value:

```yaml
webhook:
  stream:
    maxBytesByStream:
      github: 10GB
```

The map also accepts `gitlab`, `slack`, and `outline`. Values use the same format as `maxBytes`. Keep the sum of the effective stream bounds within `nats.jetstream.maxFileBytes`.

If you set `HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB` directly, replace it with `HEPHAESTUS_WEBHOOK_STREAM_MAXBYTESBYSTREAM_GITHUB`. The same `MAXBYTESBYSTREAM_<PROVIDER>` form sets other map entries. The old chart field is rejected, and the server no longer reads the old variable.
