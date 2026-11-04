---
"hephaestus": patch
---

The GitHub sync of labels, milestones, collaborators and issue types no longer holds a database connection while it waits for GitHub. A slow or rate-limited GitHub no longer makes other requests wait for a database connection.

An interrupted issue type sync no longer deletes the issue types that it did not reach.

A new metric, `database_transaction_http_requests_total`, counts the requests to GitHub, GitLab and other services that the server sends while it holds a database connection, for each place in the server that opens a transaction.
