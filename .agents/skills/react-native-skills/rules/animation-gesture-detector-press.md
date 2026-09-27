---
title: Preserve Accessible Activation in Animated Controls
impact: HIGH
tags: animation, gestures, press, accessibility
---

## Preserve accessible activation in animated controls

Use a native control or Pressable for an ordinary action. Retain its accessible name, role,
disabled state and activation behavior when adding feedback. A GestureDetector around an
Animated.View does not by itself supply button semantics or assistive-technology activation.

For a gesture-driven interaction that needs UI-thread feedback, use the installed Gesture
Handler and Reanimated APIs. An unsuccessful or cancelled tap must not invoke the action.
Preserve a single accessible activation path and respect reduced motion. Do not replace every
Pressable with a custom gesture solely to animate opacity or scale.

References: [React Native accessibility](https://reactnative.dev/docs/accessibility),
[Gesture Handler](https://docs.swmansion.com/react-native-gesture-handler/),
[Reanimated accessibility](https://docs.swmansion.com/react-native-reanimated/docs/guides/accessibility/).
