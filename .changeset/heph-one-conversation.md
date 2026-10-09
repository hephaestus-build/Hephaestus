---
"hephaestus": minor
---

Heph now keeps one conversation with you in each workspace. The Heph page and the Heph panel show all of it. Your Slack direct message shows what you ask there. You can reply to any message in a thread.

Heph answers where you asked. A question from Slack gets its answer in Slack. A question from the app gets its answer only in the app. The app shows everything, with a Slack marker on messages written in Slack.

You can send another message while Heph answers, stop an answer, rate an answer as **Helpful** or **Not helpful**, and delete your own messages. Heph no longer says that it is busy and no longer needs time to get ready. **New chat**, the chat history list, regenerate and edit-and-resend are gone. The upgrade deletes earlier Heph chat history.

**Operators:** stop every role (application server, worker and webhook receiver) before you upgrade, because the upgrade drops the old Heph chat tables. Take a database backup first if you want a way back. Update the Slack app from the manifest template and reinstall it, so that it gets the `reactions:write` scope, then select **Reconnect Slack** in each workspace that uses it. Rename `HEPHAESTUS_RATE_LIMIT_MENTOR_CHAT_*` to `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_*`. The Traefik sticky-cookie labels, the Heph sandbox settings under `hephaestus.mentor.` that the migration guide lists, and the **Internet access** switch of the Heph model assignment are removed. That switch gave Heph's sandbox internet access. The Heph host now runs on an internal network, with no internet access. Each worker now starts one Heph host container for all Heph answers, so the recommended single host rises from 16 GB to 24 GB of memory. Some Heph metrics changed. The migration guide lists the removed settings and metrics. `HEPHAESTUS_WORKER_MENTOR_MAX` sets how many Heph answers the Heph host of one worker writes at once. A Heph answer whose model provider stays silent too long (5 minutes before it answers, 2 minutes within an answer) now fails with a message that says so, and Heph refuses a model with a context window below 32,768 tokens.
