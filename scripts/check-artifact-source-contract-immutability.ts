import { execFileSync } from "node:child_process";

import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

// Git hooks export GIT_DIR and GIT_INDEX_FILE, which would silently redirect every command below at
// whichever repository invoked the hook. This check is about the working tree it was pointed at, so
// it resolves that itself.
const env = Object.fromEntries(
	Object.entries(process.env).filter(([key]) => !key.startsWith("GIT_")),
);

// Anchored on the repository root, not the caller's cwd. A relative contract path resolves to
// nothing from a subdirectory, and `git diff` over a path that matches no file reports no
// difference — so the check would pass having compared nothing, and say so in the same words.
const repoRoot = execFileSync("git", ["rev-parse", "--show-toplevel"], {
	encoding: "utf8",
	env,
}).trim();
const root = "server/application/src/main/resources/contracts/artifact-source";
const githubBaseRef = process.env.GITHUB_BASE_REF;
const baseRef =
	process.env.CONTRACT_BASE_REF ??
	(githubBaseRef !== undefined && githubBaseRef !== "" ? `origin/${githubBaseRef}` : "origin/main");

const git = (...args: string[]): string =>
	execFileSync("git", args, {
		cwd: repoRoot,
		encoding: "utf8",
		env,
		// A diff over the contract tree has no useful size limit.
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});

const base = git("merge-base", "HEAD", baseRef).trim();

// merge-base with the branch's own tip (a push build on the base branch) leaves nothing to compare,
// so every diff is empty and the check would report success having compared a commit with itself.
if (base === git("rev-parse", "HEAD").trim()) {
	console.log(
		`Artifact-source contract immutability: HEAD is the merge base with ${baseRef}; nothing to compare.`,
	);
	process.exit(0);
}

const rootExists = git("ls-tree", "-d", "--name-only", base, "--", root).trim() === root;
const publishedVersions = rootExists
	? git("ls-tree", "-d", "--name-only", `${base}:${root}`).trim().split("\n").filter(Boolean)
	: [];

if (publishedVersions.length === 0) {
	console.log(
		`Artifact-source contract immutability: no version is published at ${base.slice(0, 8)} yet.`,
	);
	process.exit(0);
}

for (const version of publishedVersions) {
	const versionPath = `${root}/${version}`;
	// --quiet exits non-zero on any difference, which covers edits, deletions, and the rename of a
	// published directory (its old path reads as deleted).
	try {
		git("diff", "--quiet", base, "--", versionPath);
	} catch {
		throw new Error(
			`Published artifact-source contract ${version} is immutable; add a new version.`,
		);
	}
}

console.log(
	`Artifact-source contract immutability: ${publishedVersions.length} published version(s) unchanged since ${base.slice(0, 8)} (${publishedVersions.join(", ")}).`,
);
