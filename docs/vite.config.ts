import { defineConfig } from "vite-plus";

import { loadLintConfig } from "../webapp/tools/oxlint/load-config.ts";

const lint = loadLintConfig(new URL(".oxlintrc.json", import.meta.url));

export default defineConfig({ root: import.meta.dirname, lint });
