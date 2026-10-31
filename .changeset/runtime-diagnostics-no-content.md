---
"hephaestus": patch
---

The worker's `sandbox.exited` log record no longer copies text from inside a review sandbox. Before, its `sandbox.stderr.tail` field could quote a tool result, a provider's error message or a review's refusal reason. Such text can hold reviewed code or a credential. The review runtime now writes only coded diagnostic lines of counts, flags and closed codes, such as `[pi-runner] tool.failed phase=review turn=1 practice=0 tool=bash reason_chars=42`. A practice is named by its position in the review, not by its name. The worker keeps a line only when every part of it is in the runtime's diagnostic vocabulary, also from an older runtime image. It counts every other line in the new field `sandbox.stderr.dropped`.

If you ship worker logs to a log store, records written before this release can still hold such text. Apply your store's retention to them, or delete them.
