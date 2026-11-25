---
---

The browser tests now wait for the web app to mark its page idle, a `data-page-idle` attribute on the document while nothing loads, instead of waiting for network silence. A lint rule refuses the old wait. Nothing that users or operators see changes.
