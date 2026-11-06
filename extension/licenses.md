# Supplemental license texts

The extension build uses Vite to generate notices for bundled dependencies.
Three npm packages omit their license files from their published tarballs.
Their complete upstream license texts ship under `public/THIRD_PARTY_NOTICES/`:

| File | Packages | Upstream source |
| --- | --- | --- |
| `wxt-LICENSE` | `wxt` 0.21.5 and `@wxt-dev/browser` 0.3.7 | [WXT license at the 0.21.5 source](https://github.com/wxt-dev/wxt/blob/e690f91730aa679a58fba905f30f8ca80ea4f490/LICENSE) |
| `webext-core-LICENSE` | `@webext-core/isolated-element` 3.0.0 | [Isolated-element 3.0.0 source license](https://github.com/aklinker1/webext-core/blob/b80a9ea9ce1894add1fc6e0963f1d8d250bcbb8d/LICENSE) |

When you update these packages, compare these files with the corresponding upstream source licenses.
WXT copies them as public assets. No license download runs during a build.
