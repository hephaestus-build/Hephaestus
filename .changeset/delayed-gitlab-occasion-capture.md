---
"hephaestus": patch
---

Queued GitLab reviews now preserve the original code range when Hephaestus recorded that range at the time of the request. New commits or a moved target branch no longer substitute a different range. This applies to new review requests with a recorded range. Unknown original ranges and stopped reviews are not recovered or retried.

Before posting feedback on a merge request, Hephaestus also checks the captured code range against the current recorded range. A changed range withholds feedback. An unknown current range uses the existing bounded retry. This check also applies to prepared reviews requested earlier. Comments and review status are still read when the review runs.
