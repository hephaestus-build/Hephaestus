import { defineConfig } from "vite-plus";

import { loadLintConfig } from "../webapp/tools/oxlint/load-config.ts";

// Astro builds the site (`astro.config.ts`). This file gives `vp lint` the docs tree's lint configuration.
const lint = loadLintConfig(new URL(".oxlintrc.json", import.meta.url));

export default defineConfig({ root: import.meta.dirname, lint });
