---
---

No change for operators or people. The server test tiers no longer fail on two short waits that only the JDK causes: a first load of a class that another thread loads at the same time, and a finalizer registration. A wait in a class initializer or a native call still fails them.
