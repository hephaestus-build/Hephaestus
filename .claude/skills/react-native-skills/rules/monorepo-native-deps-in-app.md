---
title: Declare Native Dependencies in the Mobile App
impact: HIGH
tags: monorepo, dependencies, autolinking
---

## Declare native dependencies in the mobile app

Declare the native modules the app uses in its own package manifest. Modern Expo autolinking
can search transitive dependencies; it is not restricted to the app's immediate node_modules.
An explicit app dependency makes the native runtime's inputs clear and keeps its version
selection under the application's control.

Use the installed Expo SDK's autolinking and Metro defaults. Do not add custom search paths,
hoisting rules, or resolver aliases unless an actual resolution failure requires them. A native
module change requires a new binary; an OTA update cannot install native code.

References: [Expo autolinking](https://docs.expo.dev/modules/autolinking/),
[Expo monorepos](https://docs.expo.dev/guides/monorepos/).
