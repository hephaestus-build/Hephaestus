#### 🔴 Public activity pages count contributors without an account instead of naming them

After the upgrade, a published public activity page no longer names contributors who have no Hephaestus account that chose to show. It shows their number instead, as "and N more contributors". To keep naming them, turn on **Show contributors without an account** in the workspace's public activity page settings. Consider the legal basis first: your record of processing should state why you publish people who have not chosen it.

API clients that call `PATCH /workspaces/{workspaceSlug}/public-activity` must send `contributorsWithoutAccount` as well as `publicActivityEnabled` and `allowSearchEngines`. All three fields are required.
