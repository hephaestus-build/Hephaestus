---
"hephaestus": patch
---

A data export that you request from this release on finishes when a server restart interrupts it. Before, the export stayed in progress, and every new export request failed with "An export is already in progress". An export that a restart interrupted before this release stays in progress.
