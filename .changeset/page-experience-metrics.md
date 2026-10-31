---
"hephaestus": minor
---

Operators can turn on page experience metrics to see how fast pages load and respond for signed-in readers. The setting is off by default. Turn it on with `PAGE_EXPERIENCE_METRICS=true`, or `config.pageExperienceMetrics: true` in the Helm chart. In the EU, this can require consent or a documented legal basis, so read [Page experience](https://docs.hephaestus.build/admin/legal-pages#page-experience) first.

When it is on, the web app reports each visit's largest contentful paint, interaction to next paint, layout shift and time to first byte. The server exposes them as `web_vital_duration_seconds` and `web_vital_layout_shift_thousandths`. A report names no person, workspace or page address. [Page experience metrics](https://docs.hephaestus.build/admin/observability#page-experience-metrics) lists the series.
