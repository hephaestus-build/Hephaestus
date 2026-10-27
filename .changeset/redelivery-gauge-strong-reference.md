---
---

Tests only in effect: the unrecovered GitHub webhook gauge holds its own value, so a garbage collection between a redelivery run and the read no longer reports NaN in a test. The running server keeps its redelivery job for its whole life, so its metric is unchanged.
