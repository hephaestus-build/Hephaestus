---
"hephaestus": patch
---

Feedback on the reviewed work now goes out through the same outbox as email and product feedback issues. Each post, correction, readback and withdrawal of a feedback is one action in that feedback's own lane, so the sends of one feedback never overlap and run in order. A send that may have reached GitHub or GitLab looks for its copy by its marker before it writes again. A send that was waiting or in progress during the upgrade runs again after it and reads back first. A manual retry or recheck is a new send with its own attempts, so `practice_feedback_send_attempts` no longer counts the attempts of earlier sends. Operators can watch these sends in the `action.ended` metric with `kind=DELIVER_FEEDBACK`, `EDIT_FEEDBACK` or `RETRACT_FEEDBACK`.
