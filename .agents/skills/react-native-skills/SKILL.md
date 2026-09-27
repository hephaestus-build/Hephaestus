---
name: react-native-skills
description: Build and review Hephaestus mobile screens, native navigation, gestures, lists, and Expo integrations. Use for mobile React Native work; the existing web client has its own guidance.
license: MIT
metadata:
  author: vercel
  source: https://github.com/vercel-labs/agent-skills/tree/063bee94c3f4df8453406c830b0a7df0f2860278/skills/react-native-skills
  upstream-version: "1.0.0"
---

# React Native skills

Vercel's React Native references, adapted for Hephaestus. Start with the mobile tree's
instructions and the task's actual component; load only the matching references below.
The web client's UI, routing and state remain independent.

| Work in front of you | References to open |
|---|---|
| Feed or chat scrolling | [Virtualization](rules/list-performance-virtualize.md); other `list-performance-*` files only for the observed bottleneck |
| Stack, tabs, sheets or menus | [Native navigation](rules/navigation-native-navigators.md), [sheets](rules/ui-native-modals.md), [menus](rules/ui-menus.md) |
| Keyboard, insets or measurement | [Safe areas](rules/ui-safe-area-scroll.md), [scroll insets](rules/ui-scrollview-content-inset.md), [measurement](rules/ui-measure-views.md) |
| Gestures and animation | [Press feedback](rules/animation-gesture-detector-press.md), [animation properties](rules/animation-gpu-properties.md); other animation and scroll-position references as needed |
| Native dependency or font changes | [Autolinking](rules/monorepo-native-deps-in-app.md), [versions](rules/monorepo-single-dependency-versions.md), [font embedding](rules/fonts-config-plugin.md) |
| Reusable UI or rendering defect | [Component boundaries](rules/imports-design-system-folder.md), [text](rules/rendering-text-in-text-component.md), [conditional rendering](rules/rendering-no-falsy-and.md) |
| React Compiler interaction | [Hook functions](rules/react-compiler-destructure-functions.md), [shared values](rules/react-compiler-reanimated-shared-values.md) |

The remaining rule files are optional references for their named concern, not a checklist
for every change. Upstream examples may use a different library version: the installed Expo,
React Native and library APIs decide the implementation. Read compiler guidance only when the
mobile build enables it; the web client's compiler setting does not establish mobile's.

Prefer native conventions on each platform. The iOS inset, sheet and corner examples do not
establish Android behavior. Keep accessibility semantics, text scaling and reduced-motion
behavior when adopting an animation or gesture example. A named library in a reference is an
option for a concrete feature, not a reason to install it.

Local corrections cover autolinking, renderer version isolation, bounded lists, accessible
press feedback, component boundaries, compiler claims and platform-specific insets. The
compiled upstream guide and redundant quick reference are omitted so a screen edit does not
load the entire pack. Update from the pinned source above, review the adaptations, then mirror
the skill into `.agents/skills`; `gate:instructions` checks that both tools read the same content.
Upstream declares the MIT license in its skill frontmatter; retain that attribution.
