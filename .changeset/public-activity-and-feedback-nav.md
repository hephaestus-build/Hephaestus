---
"hephaestus": minor
---

A public activity page no longer names contributors who have no account that chose to show. It counts them instead, as "and N more contributors". A workspace administrator can name them with **Show contributors without an account** in the public activity page settings, and turn it off again.

Turning that choice on asks for confirmation before it publishes names. Turning it off takes effect at once.

The public activity page also no longer sends the logins of the people who helped most, which no page showed. In instance administration, the product feedback entry is now called Reports.

Public activity settings save only the choice you change, so one save cannot restore names that another save hid.

**Operators:** After the upgrade, published public activity pages stop naming contributors without an account. To keep naming them, a workspace administrator turns on **Show contributors without an account**. API clients that call `PATCH /workspaces/{workspaceSlug}/public-activity` can omit the settings that they do not change. Follow the migration instructions before upgrading.
