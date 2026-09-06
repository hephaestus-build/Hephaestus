---
"hephaestus": patch
---

An instance that used the mentor before its chat storage changed can now upgrade without a
hand-run migration. The upgrade previously stopped at a step that refuses to remove the old
chat-parts table while it still holds rows, and nothing ever emptied it, so the application stayed
down on exactly the installations that had chat history. The history itself is carried onto the
message, as that step always intended.
