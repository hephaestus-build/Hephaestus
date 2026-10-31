import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";

import { isSet } from "./lib/env.ts";
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
const root = "server/application/src/main/resources/contracts/source-use";
const githubBaseRef = process.env.GITHUB_BASE_REF;
const baseRef =
	process.env.CONTRACT_BASE_REF ??
	(isSet(githubBaseRef) ? `origin/${githubBaseRef}` : "origin/main");

// Numeric collation orders "1.10.0" after "1.9.0", as semantic versions do.
const compareVersions = new Intl.Collator("en", { numeric: true }).compare;

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
		`Source-use contract immutability: HEAD is the merge base with ${baseRef}; nothing to compare.`,
	);
	process.exit(0);
}

const rootExists = git("ls-tree", "-d", "--name-only", base, "--", root).trim() === root;
const publishedVersions = rootExists
	? git("ls-tree", "-d", "--name-only", `${base}:${root}`).trim().split("\n").filter(Boolean)
	: [];

if (publishedVersions.length === 0) {
	console.log(
		`Source-use contract immutability: no version is published at ${base.slice(0, 8)} yet.`,
	);
	process.exit(0);
}

// The server loads only the newest version, so an older version may leave the tree whole; git keeps it.
// The newest version, and any version that is still present, must keep every published byte.
const newest = publishedVersions.toSorted(compareVersions).at(-1);
const retired: string[] = [];
for (const version of publishedVersions) {
	if (version !== newest && !existsSync(path.join(repoRoot, root, version))) {
		retired.push(version);
		continue;
	}
	const publishedFiles = git("ls-tree", "-r", "--name-only", `${base}:${root}/${version}`)
		.trim()
		.split("\n")
		.filter(Boolean);
	for (const file of publishedFiles) {
		const expected = git("show", `${base}:${root}/${version}/${file}`);
		try {
			if (readFileSync(path.join(repoRoot, root, version, file), "utf8") !== expected) {
				throw new Error("Changed bytes");
			}
		} catch {
			throw new Error(`Published source-use contract ${version} is immutable; add a new version.`);
		}
	}
}

// A version string names one set of bytes forever. A directory that the base does not publish is a new version only
// when it is above every published one, so a retired version cannot return with other bytes.
const presentVersions = existsSync(path.join(repoRoot, root))
	? readdirSync(path.join(repoRoot, root), { withFileTypes: true })
			.filter((entry) => entry.isDirectory())
			.map((entry) => entry.name)
	: [];
for (const version of presentVersions) {
	if (
		!publishedVersions.includes(version) &&
		newest !== undefined &&
		compareVersions(version, newest) <= 0
	) {
		throw new Error(
			`Source-use contract ${version} is not above the newest published version ${newest}; add a new version.`,
		);
	}
}

const kept = publishedVersions.filter((version) => !retired.includes(version));
console.log(
	`Source-use contract immutability: ${kept.length} published version(s) unchanged since ${base.slice(0, 8)} (${kept.join(", ")})${retired.length > 0 ? `; retired whole: ${retired.join(", ")}` : ""}.`,
);
