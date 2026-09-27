---
title: Account for Hook Object Identity at Memoization Boundaries
impact: MEDIUM
tags: react-compiler, hooks, memoization
---

## Account for hook object identity at memoization boundaries

Apply this only where React Compiler is enabled. Some hooks return a new result object each
render while individual methods remain stable. Destructuring the methods used by a memoized
closure can avoid depending on the entire result object. Reading a method through a property
does not itself create a new function; stability depends on that hook's implementation.

Follow the installed hook's contract and the compiler diagnostics. Do not mechanically rewrite
all method calls, or add manual memoization to every list callback.

Reference: [React Compiler](https://react.dev/learn/react-compiler).
