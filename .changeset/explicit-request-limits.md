---
"hephaestus": patch
---

The server now states its request limits instead of relying on defaults: a request header block of at most 8 KB, a form body of at most 2 MB, and at most 2 MB of an unread body. Tomcat refuses a larger request before any application code runs. The sandbox gateway applies the same header limit, which it did not receive from the server's settings before. The values are the ones the server already used, so no operator action is needed.
