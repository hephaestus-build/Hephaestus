---
"hephaestus": patch
---

The server log now records each account that a person deletes themselves, as the `account.deleted` event with the account ID only. Keep these events for as long as your backups: after a database restore, they name the accounts that the erasure recovery must delete again.
