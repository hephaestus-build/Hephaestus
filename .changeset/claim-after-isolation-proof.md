---
"hephaestus": patch
---

On Kubernetes, a worker that has just started no longer takes practice reviews or Heph turns before its sandbox isolation probe passed. Until then, the work stays queued, so reviews no longer fail and conversations no longer show "Heph could not start" after a deploy.
