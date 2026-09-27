---
title: Virtualize Growing Feeds and Conversation History
impact: HIGH
tags: lists, performance, virtualization, scrollview
---

## Virtualize growing feeds and conversation history

Use a virtualized list for unbounded feedback feeds and Heph conversation history. Select the
list for the interaction it must support: variable-height streamed messages, prepending older
history, preserving scroll position, and avoiding jumps when the keyboard appears. Use the
app's existing list implementation when it meets those needs.

A small, bounded settings page can use ScrollView. Virtualization is not automatically an
improvement for a handful of static controls. Avoid nested lists scrolling on the same axis.
Give rows stable product identities, and account for recycled row state when using a recycler.

References: [React Native lists](https://reactnative.dev/docs/optimizing-flatlist-configuration),
[LegendList](https://legendapp.com/open-source/list/),
[FlashList](https://shopify.github.io/flash-list/).
