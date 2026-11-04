#### 🔴 Replace `worker.reviews` with the memory that review Pods get

The chart no longer reads a review count per worker. It derives the reviews that run at once from `worker.sandbox.reviewMemory`, the memory that your sandbox nodes give review Pods, and the request of one review Pod. Each worker runs as many of them as its memory limit holds, and the reviews quota holds them once. The values schema refuses `worker.reviews`, so a values file that still sets it stops the render.

**Action:** remove `worker.reviews` from your values file. To keep the same number of reviews, set `worker.sandbox.reviewMemory` to your old `worker.reviews` times `worker.replicas` times 384Mi. For example, 2 workers of 5 reviews become `reviewMemory: 3840Mi`, which is the default. With a RuntimeClass overhead in `worker.runtime.podOverhead`, add that overhead to the 384Mi.

The check on `worker.resources.limits.memory` now also counts the worker's 128 MiB of direct memory, which the JVM takes from the heap. One review needs at least 1023 MiB, and each further review 18 MiB. A limit that the old check passed can now hold fewer reviews per worker, and a limit below 1023 MiB stops the render with `worker.resources.limits.memory must be at least 1023Mi`. To keep the same reviews per worker, raise the limit by 128 MiB.
