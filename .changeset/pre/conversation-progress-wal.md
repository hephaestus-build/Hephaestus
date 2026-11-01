---
"hephaestus": patch
---

A streaming answer in a conversation no longer rewrites itself in the database while Hephaestus writes it. The answer is stored once, when it ends, and the words in between reach the page and Slack without a database write. A long answer now writes about as much to the database's write-ahead log as its own size.
