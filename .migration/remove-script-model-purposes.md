#### 🔴 Stop every role before the models for practice scripts are deleted

Practice scripts make no direct model call any more, so the upgrade deletes what served those calls.

1. Note the instance connections that use the Decisions, Embeddings or Rerank API, if you want to keep their settings elsewhere. The upgrade deletes them, their models, and the decision, embedding and reranking assignments of every workspace.
2. Stop the server, worker, and webhook roles. The upgrade drops the table that the previous release writes script usage to.
3. Back up the database.
4. Upgrade. The migration also rewrites the frozen review method of each review and the snapshot of each queued job to the new shape.
5. Remove `PRACTICE_REVIEW_DIRECT_MODEL_TOKENS_PER_ATTEMPT` and `PRACTICE_REVIEW_DIRECT_MODEL_SEARCH_UNITS_PER_ATTEMPT` from your configuration if you set them.
6. Start the roles.

Past usage of these models stays in **AI usage**. If the upgrade stops, restore the verified backup before you start the previous release.
