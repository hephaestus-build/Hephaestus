---
name: react-best-practices
description: |
  React performance optimization guidelines from Vercel Engineering. Contains 45 rules across
  8 categories. Use when optimizing React components, bundle size, or data fetching patterns.

  IMPORTANT: This codebase uses Vite + TanStack Router (SPA), NOT Next.js.
  See applicability notes below for which rules apply.
license: MIT
metadata:
  author: vercel
  version: "1.0.0"
---

# Vercel React Best Practices

Follow the [writing standard](../../../docs/contributor/simplified-technical-english.md) for all prose.

Comprehensive performance optimization guide for React applications, maintained by Vercel.
Contains 45 rules across 8 categories, prioritized by impact.

## Applicability

**This codebase is a client-side SPA: Vite + TanStack Router, React 19, TanStack Query, a Spring Boot
backend. There is no Next.js, no RSC and no SSR.** The pack is written for Next.js, so read this
section before any rule file.

### Skip — the API or the runtime does not exist here

| Rule | Why |
|------|-----|
| `async-api-routes` | Next.js API routes. The backend is Spring Boot |
| `async-suspense-boundaries` | About streaming an RSC tree. Nothing in the SPA renders on a server |
| `server-cache-react` | `React.cache()` is RSC-only |
| `server-cache-lru` | Server-side caching, which is the backend's problem |
| `server-serialization` | RSC client/server boundary |
| `server-parallel-fetching` | RSC data fetching. TanStack Query is the equivalent here |
| `server-after-nonblocking` | `after()` from `next/server` |
| `rendering-hydration-no-flicker` | SSR hydration. The build is pure CSR |
| `client-swr-dedup` | SWR. This repo uses TanStack Query, which dedups by query key |
| `rerender-memo` | React Compiler is on — see below |

### Apply, with the API substituted

| Rule | Read it as |
|------|-----------|
| `bundle-dynamic-imports` | The reasoning holds and the repo has the rule's own example (`@monaco-editor/react`). The mechanism is `React.lazy()` plus TanStack Router's `autoCodeSplitting`, not `next/dynamic` |

### Apply as written

`async-defer-await`, `async-parallel`, `async-dependencies`, `bundle-barrel-imports`,
`bundle-defer-third-party`, `bundle-conditional`, `bundle-preload`, `client-event-listeners`.
Every `rerender-*` except `rerender-memo`. Every `rendering-*` except `rendering-hydration-no-flicker`
(this includes `rendering-activity` — React is 19.2+, so `<Activity>` exists). Every `js-*`. Every
`advanced-*`.

### React Compiler

`webapp/vite.shared.ts` passes `compiler: true` to `@vitejs/plugin-react` for every build of app
source, so **do not add `useMemo`, `useCallback` or `React.memo`**. A hand-written one that survives
in the tree is load-bearing. Deleting it breaks something that still type-checks
(`webapp/AGENTS.md` § React Compiler).

## When to Apply

Reference these guidelines when:
- Writing new React components
- Implementing client-side data fetching
- Reviewing code for performance issues
- Optimizing bundle size or load times
- **NOT** when working with server-side patterns (this is a SPA)

## Rule Categories by Priority

| Priority | Category | Impact | Prefix |
|----------|----------|--------|--------|
| 1 | Eliminating Waterfalls | CRITICAL | `async-` |
| 2 | Bundle Size Optimization | CRITICAL | `bundle-` |
| 3 | Server-Side Performance | HIGH | `server-` |
| 4 | Client-Side Data Fetching | MEDIUM-HIGH | `client-` |
| 5 | Re-render Optimization | MEDIUM | `rerender-` |
| 6 | Rendering Performance | MEDIUM | `rendering-` |
| 7 | JavaScript Performance | LOW-MEDIUM | `js-` |
| 8 | Advanced Patterns | LOW | `advanced-` |

## Quick Reference

### 1. Eliminating Waterfalls (CRITICAL)

- `async-defer-await` - Move await into branches where actually used
- `async-parallel` - Use Promise.all() for independent operations
- `async-dependencies` - Use better-all for partial dependencies
- `async-api-routes` - Start promises early, await late in API routes
- `async-suspense-boundaries` - Use Suspense to stream content

### 2. Bundle Size Optimization (CRITICAL)

- `bundle-barrel-imports` - Import directly, avoid barrel files
- `bundle-dynamic-imports` - Use next/dynamic for heavy components
- `bundle-defer-third-party` - Load analytics/logging after hydration
- `bundle-conditional` - Load modules only when feature is activated
- `bundle-preload` - Preload on hover/focus for perceived speed

### 3. Server-Side Performance (HIGH)

- `server-cache-react` - Use React.cache() for per-request deduplication
- `server-cache-lru` - Use LRU cache for cross-request caching
- `server-serialization` - Minimize data passed to client components
- `server-parallel-fetching` - Restructure components to parallelize fetches
- `server-after-nonblocking` - Use after() for non-blocking operations

### 4. Client-Side Data Fetching (MEDIUM-HIGH)

- `client-swr-dedup` - Use SWR for automatic request deduplication
- `client-event-listeners` - Deduplicate global event listeners

### 5. Re-render Optimization (MEDIUM)

- `rerender-defer-reads` - Do not subscribe to state only used in callbacks
- `rerender-memo` - Extract expensive work into memoized components
- `rerender-dependencies` - Use primitive dependencies in effects
- `rerender-derived-state` - Subscribe to derived booleans, not raw values
- `rerender-functional-setstate` - Use functional setState for stable callbacks
- `rerender-lazy-state-init` - Pass function to useState for expensive values
- `rerender-transitions` - Use startTransition for non-urgent updates

### 6. Rendering Performance (MEDIUM)

- `rendering-animate-svg-wrapper` - Animate div wrapper, not SVG element
- `rendering-content-visibility` - Use content-visibility for long lists
- `rendering-hoist-jsx` - Extract static JSX outside components
- `rendering-svg-precision` - Reduce SVG coordinate precision
- `rendering-hydration-no-flicker` - Use inline script for client-only data
- `rendering-activity` - Use Activity component for show/hide
- `rendering-conditional-render` - Use ternary, not && for conditionals

### 7. JavaScript Performance (LOW-MEDIUM)

- `js-batch-dom-css` - Group CSS changes through classes or cssText
- `js-index-maps` - Build Map for repeated lookups
- `js-cache-property-access` - Cache object properties in loops
- `js-cache-function-results` - Cache function results in module-level Map
- `js-cache-storage` - Cache localStorage/sessionStorage reads
- `js-combine-iterations` - Combine multiple filter/map into one loop
- `js-length-check-first` - Check array length before expensive comparison
- `js-early-exit` - Return early from functions
- `js-hoist-regexp` - Hoist RegExp creation outside loops
- `js-min-max-loop` - Use loop for min/max instead of sort
- `js-set-map-lookups` - Use Set/Map for O(1) lookups
- `js-tosorted-immutable` - Use toSorted() for immutability

### 8. Advanced Patterns (LOW)

- `advanced-event-handler-refs` - Store event handlers in refs
- `advanced-use-latest` - useLatest for stable callback refs

## How to Use

Read individual rule files for detailed explanations and code examples:

```
rules/async-parallel.md
rules/bundle-barrel-imports.md
rules/_sections.md
```

Each rule file contains:
- Brief explanation of why it matters
- Incorrect code example with explanation
- Correct code example with explanation
- Additional context and references

## Full Compiled Document

For the complete guide with all rules expanded: `AGENTS.md`
