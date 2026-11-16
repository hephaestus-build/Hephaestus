/**
 * Every page path that a published release's sitemap lists keeps working: `docs/released-paths.txt` names them, and
 * each needs a built page or a redirect from `docs/src/lib/released-redirects.ts`, which Astro also writes as a page.
 * Run in `docs/` after the build: `node ../scripts/check-docs-released-paths.ts`.
 */
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

/** The released paths that the build has no file for. `exists` answers for a path below the build directory. */
export function missingReleasedPaths(
	released: string,
	exists: (file: string) => boolean,
): string[] {
	return released
		.split("\n")
		.map((line) => line.trim())
		.filter((line) => line !== "" && !line.startsWith("#"))
		.filter((route) => {
			const page = route.replace(/^\//u, "").replace(/\/$/u, "");
			return page === ""
				? !exists("index.html")
				: !exists(`${page}.html`) && !exists(`${page}/index.html`);
		});
}

if (process.argv[1] === import.meta.filename) {
	const docs = path.resolve(import.meta.dirname, "..", "docs");
	const missing = missingReleasedPaths(
		readFileSync(path.join(docs, "released-paths.txt"), "utf8"),
		(file) => existsSync(path.join(docs, "build", file)),
	);
	if (missing.length > 0) {
		console.error(
			`Released paths without a page or a redirect (docs/src/lib/released-redirects.ts):\n${missing.map((route) => `  ${route}`).join("\n")}`,
		);
		process.exit(1);
	}
	console.log("Every released path has a page or a redirect.");
}
