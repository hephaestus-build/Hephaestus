#### 🔴 Remove `PRACTICE_REVIEW_PRACTICE_CONCURRENCY`, and check two review budgets

A practice review now sets its own pace, so Hephaestus no longer reads `PRACTICE_REVIEW_PRACTICE_CONCURRENCY`.

If you set this variable, remove it. If you used a low value to protect your model provider, set a limit on its [call pool](https://docs.hephaestus.build/admin/runtime-roles#call-pools) instead.

If you set `PRACTICE_REVIEW_PRACTICE_MODEL_CALLS` or `PRACTICE_REVIEW_PRACTICE_OUTPUT_TOKENS`, multiply the value by 3 to keep your budget. Each practice now gets exactly the value that you set. The old value applied at least three times over.
