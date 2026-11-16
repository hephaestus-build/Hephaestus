/**
 * Runs Astro for the docs site: `node scripts/docs-astro.ts <astro arguments>`. Every docs task and CI job starts Astro
 * through this file, so Astro's anonymous telemetry stays off everywhere. Astro reads the setting only from the
 * environment or from a file in the user's home directory, not from the project.
 */
import { spawnSync } from "node:child_process";
import path from "node:path";

const docs = path.resolve(import.meta.dirname, "..", "docs");
// The package exports no path to its command, so this is the file that `node_modules/.bin/astro` runs.
const astro = path.join(docs, "node_modules", "astro", "bin", "astro.mjs");

const result = spawnSync(process.execPath, [astro, ...process.argv.slice(2)], {
	cwd: docs,
	stdio: "inherit",
	env: { ...process.env, ASTRO_TELEMETRY_DISABLED: "1" },
});
process.exit(result.status ?? 1);
