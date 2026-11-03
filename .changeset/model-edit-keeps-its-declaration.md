---
"hephaestus": patch
---

Editing a model no longer loses its data-handling declaration. An API request that left out `operatedBy` used to clear the declaration, which took the model out of service for members who chose in-house or cloud models. Leaving out `operatedBy` now keeps the declaration. To remove it, send `clearDataHandling: true`. The admin form does this for you when you choose **Leave undeclared**.
