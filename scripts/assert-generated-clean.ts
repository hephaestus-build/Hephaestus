import { output } from "./lib/process.ts";

const paths = process.argv.slice(2);
if (paths.length === 0) throw new Error("Name the generated paths to check");

const pathspecs = paths.map((path) => `:(top)${path}`);
// A stale or mistyped artifact path must not silently become an empty, successful comparison.
await output("git", ["ls-files", "--error-unmatch", "--", ...pathspecs]);

// Status includes staged edits, deletions and new files without changing the index. Top-level
// pathspecs keep a caller in a subdirectory from accidentally checking an empty, unrelated path.
const changed = await output("git", [
	"--no-optional-locks",
	"status",
	"--porcelain=v1",
	"--untracked-files=all",
	"--",
	...pathspecs,
]);
if (changed.trim()) {
	console.error(changed.trimEnd());
	process.exitCode = 1;
}
