---
"hephaestus": patch
---

Release vulnerability scans now cover the server's Java runtime. Before, the scans saw the server's libraries and operating-system packages but not the JRE that runs them, so a Java vulnerability appeared in no report. Each build, release and daily rescan now matches the JRE against known vulnerabilities and judges the findings under the same policy. A release whose server image has no JRE, or a JRE other than the pinned one, is refused. No operator action is needed.
