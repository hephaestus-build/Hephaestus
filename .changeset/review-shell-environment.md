---
"hephaestus": patch
---

The shell tool of a practice review now runs with a minimal environment: the search path, the home and scratch directories, the locale and the sandbox's Git settings. The model proxy also bounds each review attempt on its own: it refuses a review's model calls past twice the calls and output tokens that the review's practices may take, so an attempt's cost no longer depends only on the review runtime. Scan reviews keep their budget.
