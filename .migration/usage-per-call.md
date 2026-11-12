#### 🔴 Stop every role before moving model usage to one row for each call

The usage ledger `llm_usage_event` gets one row for each model call.
The measurements of each review attempt and conversation attempt move into it, and their tables are dropped.
The previous release cannot write a model call's usage to the new ledger.

1. Make sure that each chat model has **Max output tokens** or **Context window**.
   After the upgrade, the proxy refuses calls to a chat model without either.
   It clamps the output limit of each request to this value.
   An OpenAI-compatible server that ignores the output-limit field can write past the upper bound of a call.
   See [Output limit](https://docs.hephaestus.build/admin/ai-providers#output-limit).
2. Stop the server, worker, and webhook roles.
3. Back up the database.
4. Upgrade. The migration does these steps:
   - Existing ledger rows stay as they are. They keep their calls count and get no run.
   - A measurement that the previous release had not written to the ledger yet, because its attempt still ran, becomes ledger rows: one for the review's own model, one for each practice and model kind of its practice scripts, and one for each conversation attempt. These rows keep the frozen model, price mode and rates. Verified calls on a free model remain free. Other moved measurements stay unpriced until the repricing pass prices them from their frozen rates, or resolves a price that was unknown.
   - Existing rows get no upper bound.
5. Start the roles.

The repricing pass runs every 15 minutes. It now prices unpriced usage of every month, oldest first, up to 5000 rows each pass.
After the upgrade, a capped budget can stay paused for a short time, until the pass prices the moved rows.
The pass can also price rows of earlier months that stayed unpriced because a price was missing, if the price now exists.
This changes the totals of those months.

If the upgrade stops, restore the verified backup before you start the previous release.
