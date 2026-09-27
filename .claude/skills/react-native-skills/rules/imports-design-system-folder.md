---
title: Give Shared Mobile Components a Product Responsibility
impact: MEDIUM
tags: components, architecture, imports
---

## Give shared mobile components a product responsibility

Keep shared mobile components together when they own a repeated visual or behavioral contract,
such as feedback status presentation or an accessible action row. Import React Native primitives
directly when no product behavior needs wrapping. Pure re-export files for every dependency add
indirection without creating a design system.

The mature web app and mobile app have separate UI implementations. Share domain contracts and
use corresponding design tokens where useful; do not introduce universal components just to
maximize shared code. A compound component is appropriate when it expresses meaningful slot
composition, not as a required shape for every small button.
