# Owned fork of `@earendil-works/pi-ai` 1.4.0

`@earendil-works__pi-ai@1.4.0.patch` changes the compiled adapters of `@earendil-works/pi-ai` 1.4.0, which the Pi SDK installs in the agent image.
It is owned code: the upstream release does not contain these changes, and no Renovate update carries them.

Owned from: https://github.com/earendil-works/pi/tree/4a080252f06caf63a525d7873c5d94d3c23887e0/packages/ai
Package: pkg:npm/%40earendil-works/pi-ai@1.4.0
License: MIT
Changes: Adds closed failure facts, call timing and validated OpenAI-compatible token usage to the provider adapters.
Files: ["@earendil-works__pi-ai@1.4.0.patch"]

## What changes and why

- `dist/api/provider-call-diagnostics.js` (new), three adapters and the shared Responses stream processor: a failed provider call records closed facts. These are the SDK error class, the numeric status and the adapter's own terminal branch. They never include the error text, body, headers or request. Once the SDK request starts, each call also records its elapsed time, and its response time when the response headers arrive. `server/application/src/main/resources/agent/pi-model-failure.ts` reads the failure facts as `*_failure`.
- `dist/api/openai-token-usage.js` (new) and the OpenAI Completions and Responses adapters: token usage counts only when it is a consistent set of non-negative integers. A proxy's `created_cache_tokens` reads like `cache_write_tokens`. Unusable telemetry never becomes recorded spend.

## Tests

`server/application/src/test/resources/agent/pi-provider-usage.spec.ts` (part of `vp run test:agents`) streams responses through the patched adapters and asserts the recorded usage and failure facts.

## Update and support

- The pull request that moves the Pi SDK owns this patch. Renovate's `Pi SDK` group moves the SDK, the overrides of its own packages and the Dockerfile pin together.
- A new `pi-ai` version fails the install with `ERR_PNPM_UNUSED_PATCH` until the patch is made again for it, in both `pnpm-workspace.yaml` files.
- Make the patch again with `pnpm patch @earendil-works/pi-ai@<version>`. Drop a hunk that the new release already contains. Then run `vp run test:agents`, and update `Owned from:`, `Package:`, `Changes:` and `Files:` above. `vp run gate:owned-code` fails until `Package:` and `Files:` name the new release.
- Upstream: the failure facts and the usage validation are candidates for `earendil-works/pi`. A hunk that upstream accepts leaves this patch in the next SDK update.

## License

`@earendil-works/pi-ai` is distributed under this license:

```text
MIT License

Copyright (c) 2025 Mario Zechner

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
