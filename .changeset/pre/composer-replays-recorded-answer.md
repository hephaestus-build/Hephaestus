---
"hephaestus": patch
---

Feedback composition now goes on when the answer of a model call is lost on its way to the composer. A dropped connection can cause this. The composer sends the same request again and gets the answer the server already recorded, with no second model call. Before, the request was refused, so the attempt ended and the feedback could end as not composed. The recorded answer is kept only while that composition attempt runs.
