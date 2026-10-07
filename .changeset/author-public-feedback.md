---
"hephaestus": minor
---

Feedback on a pull request, merge request or issue addresses its author's work. Feedback about reviewing stays with the reviewer on their own practice page or in their own conversation, subject to the existing delivery checks.

**Operators:** API responses that report why feedback was withheld can now contain the reason `PUBLIC_SUBJECT_INELIGIBLE`. If a custom API client rejects unknown enum values, update it to accept this value before you upgrade. The clients bundled with this release already accept this value.
