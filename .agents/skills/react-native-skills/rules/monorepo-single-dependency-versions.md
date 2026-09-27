---
title: Keep Each Native Runtime on Compatible Dependency Versions
impact: HIGH
tags: monorepo, dependencies, installation
---

## Keep each native runtime on compatible dependency versions

A native bundle must resolve one compatible React, React Native and native-module version.
Use the versions selected by the app's Expo SDK and check its dependency resolution with Expo's
supported tooling. Declare native dependencies in the app package.

Hephaestus web and mobile are separate applications with separate renderers. Do not force their
React versions together with a workspace override when the native renderer requires a different
version. Keep the workspace's isolated installation and release-age policy; verify the app's
actual resolved versions rather than treating a shared lockfile as proof of deduplication.

Reference: [Expo monorepo dependency guidance](https://docs.expo.dev/guides/monorepos/).
