---
"hephaestus": patch
---

Keeps the recovery pass for practices nothing observed when the first pass runs long. The pass that retries them was being given the review time left unspent, which is none after an overrun — so on exactly the slow reviews where practices are most likely still unobserved, no retry ran at all, even with minutes left before the review's deadline. It now keeps the share reserved for it whenever that time genuinely exists, and is skipped only when it does not.
