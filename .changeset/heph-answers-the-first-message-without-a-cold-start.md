---
"hephaestus": minor
---

Heph starts getting ready as soon as you open it, in the webapp or in the Hephaestus app in Slack, so your first message no longer waits for Heph to start up. Heph now stays ready for 15 minutes after your last message instead of 5. Going back to an earlier conversation while Heph is ready no longer makes it forget what was said there. Operators can see how long developers wait for the first words of a reply, split into turns that found Heph ready and turns that had to wait, in the new `mentor_turn_first_token_seconds` metric; a custom proxy setup that pins Heph traffic to one replica should keep its affinity for as long as `hephaestus.mentor.idle-ttl-seconds`, now 900 seconds by default.
