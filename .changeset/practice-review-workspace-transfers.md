---
"hephaestus": patch
---

Practice review workspace transfers now support authenticated area and repository selections from the same frozen archive. Transfers above the 512 MiB workspace budget are refused before launch; repeated selections do not spend the budget again. Verified evidence folders are removed after admission, and a worker restart removes folders for unknown or finished attempts.
