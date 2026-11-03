---
"hephaestus": patch
---

A data export that a server restart interrupted now finishes when the server is back. Before, the export stayed in progress, and every new export request failed with "An export is already in progress".
