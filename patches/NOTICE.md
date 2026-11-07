# Owned forks of installed packages

Each patch here changes a package that pnpm installs (`patchedDependencies` in `pnpm-workspace.yaml`).
It is owned code: the upstream release does not contain the change, and no Renovate update carries it.

## `@hey-api/openapi-ts` 0.99.0

Owned from: https://github.com/hey-api/hey-api/tree/c9dc0b94b0bfd53b8614e31f450607e1db4d3c05/packages/openapi-ts
Package: pkg:npm/%40hey-api/openapi-ts@0.99.0
License: MIT
Changes: Exports the createQueryKey and createInfiniteParams helpers of the TanStack Query plugin.
Files: ["@hey-api__openapi-ts@0.99.0.patch"]

### What changes and why

The webapp generator config (`scripts/api-generator/webapp.config.ts`) writes each operation to a module of its own, so a page loads only the operations it calls.
These modules import the two shared helpers from `@tanstack/react-query.gen.ts`.
The plugin declares both helpers without `export`, and no option exports them.
The patch adds `.export()` to the two declarations in `dist/init-D6Y8JFUS.mjs`.
The package runs only when the client is generated; nothing of it ships.

### Tests

`scripts/api-generator/operation-modules.test.ts` (part of `vp run test:tooling`) checks that each operation has its own SDK and query module.
The webapp build fails with `MISSING_EXPORT` if the helpers are not exported.

### Update and support

- The pull request that moves `@hey-api/openapi-ts` owns this patch.
- A new version fails the install with `ERR_PNPM_UNUSED_PATCH` until the patch is made again with `pnpm patch @hey-api/openapi-ts@<version>`.
- Then run `vp run generate:api:client`, `vp run test:tooling` and `vp run build:webapp`, and update the fields above.
- Upstream: the generator imports a symbol from another file without exporting it. No upstream issue is filed yet. Drop the patch when a release exports the helpers.

### License

```text
MIT License

Copyright (c) Hey API

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

## `wxt` 0.21.5

Owned from: https://registry.npmjs.org/wxt/-/wxt-0.21.5.tgz
Package: pkg:npm/wxt@0.21.5
License: MIT
Changes: Reloads the tabs of a runtime content script by its registered origins, not by its declared matches.
Files: ["wxt@0.21.5.patch"]

### What changes and why

`docs/contributor/browser-extension.mdx` § Runtime-script reloads gives the reason.
The extension registers optional site access only after the user grants it, so the entrypoint's declared `matches` is empty.
The patch makes the development reload select tabs by the registered origins.
It adds no host permission and no production code.

### Tests

No automated test covers it. Check the extension's development reload by hand.

### Update and support

- The pull request that moves `wxt` owns this patch, and checks the upstream runtime reload implementation.
- A new version fails the install with `ERR_PNPM_UNUSED_PATCH` until the patch is made again with `pnpm patch wxt@<version>`.

### License

```text
MIT License

Copyright (c) 2023 Aaron

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
