---
"hephaestus": minor
---

Page experience metrics now measure each page that a reader opens, not only the first page of a visit. `web_vital_duration_seconds` and `web_vital_layout_shift_thousandths` have a new `navigation` tag. It is `load` for the first page of a page load, and `route` for each page that the reader opened after it. Chromium 151 and later report route views. In other browsers, `load` covers the whole visit. Update the dashboards and alerts that read these series to group or filter by `navigation`.

Before, INP and layout shift covered every page of a visit but named only its first page. The interactions of a reader after they returned to a tab were lost. Now the browser also sends new measurements when the reader leaves a page again. If your privacy notice uses the text from [Page experience](https://docs.hephaestus.build/admin/legal-pages#page-experience), update it to the new text.

Each account can now send 30 reports a minute. `HEPHAESTUS_RATE_LIMIT_BROWSER_REPORTS_CAPACITY` and `HEPHAESTUS_RATE_LIMIT_BROWSER_REPORTS_PERIOD` set the limit. `PAGE_EXPERIENCE_METRICS` is no longer in the Compose example files.
