---
"hephaestus": patch
---

The bundled PostgreSQL compresses the full-page images in its write-ahead log with lz4, so streamed answers write about a third less log. It costs a little CPU for each full-page image. If you run your own PostgreSQL, set `wal_compression = lz4` (or `zstd`) on it.
