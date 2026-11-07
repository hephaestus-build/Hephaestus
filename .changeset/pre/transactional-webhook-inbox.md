---
"hephaestus": patch
---

Webhook redelivery and administrator replay skip database writes that already committed. A retry continues unfinished writes without repeating completed steps. Delivery keys expire after the longest live or configured stream retention plus one day.
