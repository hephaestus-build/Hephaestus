#### 🔴 Rename hephaestus.mentor.max-frame-chars to hephaestus.mentor.max-frame-bytes

Earlier releases counted the limit on one message from a Heph sandbox in characters. It now counts
UTF-8 bytes, and the setting is named for that. If you set `hephaestus.mentor.max-frame-chars`, set the
same value as `hephaestus.mentor.max-frame-bytes` before upgrading. The old name is ignored, and a value
left under it falls back to the default of 1 MiB, which is also the largest value allowed. The same
value admits the same plain-ASCII messages. Text with multi-byte characters uses more of it.
