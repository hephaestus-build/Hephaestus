/**
 * Every docs page in `docs/{user,admin,contributor}/` is an `.mdx` file. A repository file that names a page by a `.md`
 * path names a page that was renamed: a chart comment, a server comment, a script or a workflow keeps the old path after a rename, and
 * no other gate reads those files. Released entries in `CHANGELOG.md` and `MIGRATION.md` keep their links.
 */
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const PAGE = /docs\/(?:user|admin|contributor)\/[\w./-]*?[\w-]+\.md\b(?!x)/gu;
const RELEASED = new Set(["CHANGELOG.md", "MIGRATION.md"]);

/** The failures for `git grep -n` lines (`file:line:text`), given which repository paths exist. */
export function stalePagePaths(
	grepLines: readonly string[],
	exists: (file: string) => boolean,
): string[] {
	const failures: string[] = [];
	for (const entry of grepLines) {
		const { file, line, text } =
			/^(?<file>[^:]+):(?<line>\d+):(?<text>.*)$/u.exec(entry)?.groups ?? {};
		if (file === undefined || line === undefined || text === undefined || RELEASED.has(file)) {
			continue;
		}
		for (const [page] of text.matchAll(PAGE)) {
			// A path to no page at all is a test fixture or another gate's dead link; only a renamed page is in scope here.
			if (!exists(page) && exists(`${page}x`)) {
				failures.push(`${file}:${line} names ${page}, which is now ${page}x.`);
			}
		}
	}
	return failures;
}

if (process.argv[1] === import.meta.filename) {
	const root = path.resolve(import.meta.dirname, "..");
	const grep = spawnSync(
		"git",
		["grep", "-nIE", String.raw`docs/(user|admin|contributor)/[A-Za-z0-9_./-]+\.md`, "--", "."],
		{
			cwd: root,
			env: environmentWithoutGitRepository(),
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
		},
	);
	// POSIX extended expressions have no `\b`, so `PAGE` does the exact match. `git grep` exits 1 when nothing
	// matches, which is the passing case.
	if (grep.status !== 0 && grep.status !== 1) {
		throw new Error(`git grep failed: ${grep.stderr}`);
	}
	const failures = stalePagePaths(
		grep.stdout.split("\n").filter((line) => line !== ""),
		(file) => existsSync(path.join(root, file)),
	);
	if (failures.length > 0) {
		console.error(failures.join("\n"));
		process.exit(1);
	}
	console.log(
		"check-docs-page-paths: every docs page path in the repository names an existing page.",
	);
}
