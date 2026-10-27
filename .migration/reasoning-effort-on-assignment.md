#### 🔴 Stop every role: the reasoning effort moves from the model to its assignment

The reasoning effort is now a setting of each model assignment of a workspace, not of the model.
The previous release reads the effort from the model, so it cannot run on the new schema.

The migration does these steps:

1. It copies the effort of each model to its practice review and decision model assignments.
2. It gives each Heph assignment the effort **Low** if its model had **Medium** or more. A Heph assignment keeps a lower effort, or none.
3. It gives each Heph assignment with a timeout of 300 seconds or more a timeout of 900 seconds. A shorter timeout stays.
4. It removes the effort from the models.

Embedding and reranking assignments get no effort, because their calls take none.

**Operators:**

1. Stop every role before you upgrade.
2. After the upgrade, open the **AI models** page of each workspace, and check the reasoning effort of each assignment.
3. If a script sets `reasoningEffort` or `clearReasoningEffort` on a model through `/admin/llm/...` or `/workspaces/{slug}/llm/...`, change it to set these fields on `PUT /workspaces/{slug}/agents/{purpose}`.

A rollback of the migration gives each model the effort that all its practice review assignments agree on.
