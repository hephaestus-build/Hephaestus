---
"hephaestus": minor
---

Browser fault reports now cover more failures. A page reports an error view that it showed instead of its content, and a part that it rendered again after it failed. It also reports a script that did not load, and a request that failed with a 5xx status or did not reach the server. A failed request carries the trace id that the server returned, so `browser.request.trace_id` finds every server log record of that request. A not-found or no-access view and a request that the reader cancelled are not failures. If your privacy notice uses the text from [Browser fault reports](https://docs.hephaestus.build/admin/legal-pages#browser-fault-reports), update it to the new text.
