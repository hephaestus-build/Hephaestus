---
"hephaestus": patch
---

Reviews no longer slow down for about an hour after a server crashes or is killed while reviews run. The model capacity that the stopped server held now comes back over three minutes, not all at once, and a low model call limit is no longer cut on a few slow calls. A normal rollout did not have this problem, because it lets running calls finish first.
