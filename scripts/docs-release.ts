/**
 * Prints the release tag whose docs the site root serves (docs/README.md § Versions). The Pages workflow builds that tag
 * at the root and `main` at `/next/`. It reads the tags of the local clone, so check out with all tags first.
 */
import { execFileSync } from "node:child_process";

import { docsRelease } from "./lib/previous-release.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const tags = execFileSync("git", ["tag", "--list", "v*"], {
	encoding: "utf8",
	maxBuffer: CAPTURE_LIMIT_BYTES,
})
	.split("\n")
	.filter(Boolean);
const release = docsRelease(tags);
if (release === undefined) {
	console.error("No release tag: the site root has no release to serve.");
	process.exit(1);
}
console.log(release);
