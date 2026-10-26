---
---

Tests only: the server's test JVMs start in the application's own time zone, so a test that starts through the application's main method no longer changes the zone for later test classes.
