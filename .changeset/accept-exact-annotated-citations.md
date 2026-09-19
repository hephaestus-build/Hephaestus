---
"hephaestus": patch
---

Practice reviews now accept evidence quotes copied exactly from annotated diffs. Line numbers,
file paths and quoted content remain verified against the captured change.
The review runtime preserves quoted whitespace and asks for a correction when indentation or
punctuation differs, rather than accepting quotes that admission would later refuse.
Quotes from metadata and other text artifacts are also checked at their cited lines before admission.
