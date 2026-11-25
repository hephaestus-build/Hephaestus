---
"hephaestus": patch
---

Commit link correction no longer stalls on one commit that GitHub cannot read. GitHub answers a batch of up to 50 commits at once. When it could not read one of them, for example a commit that a force-push removed, all the answers of the batch were dropped, and the emails of that repository left the queue as unanswered after 5 attempts. Now only that commit goes without an answer, and the server logs a warning that counts it. The upgrade needs no action. To link the commits of emails that already ended unanswered, ask again for them, as **Correct commit links** → **Ask again after an upgrade from 1.0.0-rc.16 or earlier** describes.
