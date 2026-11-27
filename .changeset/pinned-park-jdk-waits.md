---
---

No change for operators or people. The integration test tier no longer fails when a virtual thread waits for a class that another thread loads. The unit tests that use `PinnedParks` no longer fail on a finalizer registration. A wait in a class initializer or a native call still fails both.
