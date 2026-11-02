---
"hephaestus": patch
---

Feedback on error handling now applies one clearer standard. A catch of every exception that returns only `false` or null around work that saves, sends or signs in, with no record of the error, now counts as a swallowed failure. A catch of one named, expected failure, or a catch whose comment explains why it is safe, does not. The practice's guide now cites OWASP, SEI CERT, MITRE, Google, Go, Rust and React. If your workspace has its own error-handling practice beside this one, keep one and set Reviewed by of the other to Nobody, because two practices can still disagree.
