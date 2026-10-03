// Precompute HINTS for changes-dependencies-deliberately. Surfaces candidates only — the LLM judges whether a
// dependency edit was deliberate (intentional add/remove/bump with an appropriate constraint) or careless
// (loosened pin, dropped version, no lockfile). General by design: a per-manifest pattern table keyed off
// the file basename, NOT a single ecosystem. Adding an ecosystem = adding a row, no engine change.
//
// The table reads changed lines, not manifest sections, so a match is a CANDIDATE dependency line: a
// package.json `engines` entry or a Cargo `[package]` `rust-version` has a dependency line's shape too, and
// only the section around it says which it is. We pair an added line against a removed line for the SAME
// name and label the constraint delta between the two lines:
//   ONLY_ADDED    — the name matches only on a + line (new, or moved or reformatted from outside the diff)
//   ONLY_REMOVED  — the name matches only on a - line (dropped, or moved or reformatted)
//   PIN_LOOSENED  — exact/narrower constraint became a range/caret/tilde (==1.2.3 -> >=1.2, 1.2.3 -> ^1.2.3)
//   PIN_DROPPED   — a version constraint was present and is now entirely absent
//   BUMPED        — same constraint shape, different version value
// Manifests that spread one dependency over several lines (Maven, XcodeGen) get no such label; their changed
// dependency lines are surfaced as raw leads.
import { globFilesSync } from "../lib/files.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// ecosystem key -> how to recognise its manifest + lockfile, and how to read a "name => constraint" line.
interface Ecosystem {
	// manifest basename test (lowercased)
	isManifest: (base: string) => boolean;
	// lockfile basenames this ecosystem writes
	lockfiles: string[];
	// extract { name, constraint } from a single manifest line, or null if it lacks a dependency line's shape.
	parse: (line: string) => { name: string; constraint: string } | null;
	// For a manifest that spreads one dependency over several lines: the changed lines to surface raw,
	// in place of parse().
	dependencyLine?: RegExp;
}

// A constraint is "loose" if it admits more than one version: range operators, caret, tilde, wildcard, or empty.
const LOOSE = /(?:^$|[\^~*]|>=|<=|>|<|\.x\b|\bx\b|\|\||\s-\s|,)/u;
// A constraint is "exact" if it pins a single version: leading ==, =, or a bare semver-ish token.
const EXACT = /^(?:==?|v)?\d/u;

function isLoose(c: string): boolean {
	const t = c.trim();
	if (t === "" || t === "*") {
		return true;
	}
	if (EXACT.test(t) && !LOOSE.test(t.replace(/^==?/u, ""))) {
		return false;
	}
	return LOOSE.test(t);
}

// A package.json string value LOOKS like a dependency constraint (semver/range/protocol) rather than a
// script body, an `engines`/`exports`/`resolutions`/`config` value, etc. The per-line JSON parser cannot
// see which object block a line sits in, so this shape guard keeps `"build": "tsc"` or `"./dist": "..."`
// out of the dependency tally. A bare "*"/"x"/"latest" and the npm pseudo-protocols (workspace:/npm:/
// file:/link:/git/http) are real dependency specifiers and are admitted.
const VERSIONISH =
	/^(?:[\^~>=<* v]|\d|x\b|latest$|workspace:|npm:|file:|link:|git[+:]|https?:|github:|gitlab:|bitbucket:)/iu;
function isVersionish(c: string): boolean {
	return VERSIONISH.test(c.trim());
}

// --- per-ecosystem line parsers (kept deliberately small + tolerant; the LLM reads full context) ---

// JSON "name": "constraint"  (package.json dependency blocks)
const reJsonDep = /^\s*"(?<name>[^"]+)"\s*:\s*"(?<constraint>[^"]*)"\s*,?\s*$/u;
// Well-known package.json scalar keys that are NOT dependencies — skip so we don't emit a "dep" hint for the
// package's own name/version/etc. Generic to npm manifests, not repo-specific.
const NPM_NON_DEP_KEYS = new Set([
	"name",
	"version",
	"description",
	"license",
	"main",
	"module",
	"type",
	"types",
	"typings",
	"homepage",
	"author",
	"private",
	"packagemanager",
	"bin",
]);
// TOML name = "constraint"  or  name = { version = "constraint" }
const reTomlDep =
	/^\s*(?<name>[A-Za-z0-9_.-]+)\s*=\s*(?:"(?<quoted>[^"]*)"|\{[^}]*version\s*=\s*"(?<table>[^"]*)"[^}]*\})/u;
// requirements.txt  name==1.2.3 / name>=1,<2 / name
const reReqDep = /^\s*(?<name>[A-Za-z0-9_.\-[\]]+)\s*(?<constraint>(?:[<>=!~]=?|@)\S.*)?$/u;
// Gemfile  gem "name", "~> 1.2"
const reGemDep = /^\s*gem\s+["'](?<name>[^"']+)["']\s*(?:,\s*["'](?<constraint>[^"']*)["'])?/u;
// Gradle  implementation("group:name:1.2.3")  or  implementation 'group:name:1.2.3'
const reGradleDep = /["'](?<coordinate>[\w.-]+:[\w.-]+):(?<constraint>[^"']*)["']/u;
// Swift PM  .package(url: "...", from: "1.2.3") / exact: "1.2.3" / "1.0.0"..."2.0.0"
const reSwiftPkg =
	/\.package\(\s*url:\s*["'](?<url>[^"']+)["'][^)]*?(?:from:\s*["'](?<from>[^"']+)["']|exact:\s*["'](?<exact>[^"']+)["']|["'](?<low>[^"']+)["']\s*\.\.[.<]\s*["'](?<high>[^"']+)["'])/u;
// go.mod  require module v1.2.3  (single-line or block-body line)
const reGoMod =
	/^\s*(?:require\s+)?(?<name>[\w./-]+\.[\w./-]+\/\S+|[\w.-]+\/\S+)\s+(?<version>v\d\S*)/u;

/** A Swift PM constraint as one word: `from:`, `exact:`, a range, or nothing when the package pins none. */
function swiftConstraint(
	fromVersion: string | undefined,
	exactVersion: string | undefined,
	rangeLow: string | undefined,
	rangeHigh: string | undefined,
): string {
	if (fromVersion !== undefined && fromVersion !== "") {
		return `from:${fromVersion}`;
	}
	if (exactVersion !== undefined && exactVersion !== "") {
		return `exact:${exactVersion}`;
	}
	if (rangeLow !== undefined && rangeLow !== "" && rangeHigh !== undefined && rangeHigh !== "") {
		return `${rangeLow}..${rangeHigh}`;
	}
	return "";
}

// Cargo.toml and pyproject.toml read dependency lines identically. The two version groups are the two
// arms of an alternation — `name = "1.2"` fills the first, `name = { version = "1.2" }` the second, and
// an inline table with no version key (`name = { features = [...] }`) fills neither.
function parseTomlDependency(line: string): { name: string; constraint: string } | null {
	const [, name, quotedVersion, tableVersion] = reTomlDep.exec(line) ?? [];
	if (name === undefined) {
		return null;
	}
	return { name, constraint: quotedVersion ?? tableVersion ?? "" };
}

const ECOSYSTEMS: Ecosystem[] = [
	{
		isManifest: (b) => b === "package.json",
		lockfiles: ["package-lock.json", "yarn.lock", "pnpm-lock.yaml", "npm-shrinkwrap.json"],
		parse: (line) => {
			// The value group matches empty (`"dep": ""`); the key group cannot, so its absence means the
			// line is not a `"key": "value"` pair at all.
			const [, name, constraint = ""] = reJsonDep.exec(line) ?? [];
			if (name === undefined) {
				return null;
			}
			// Skip the package's own scalar fields (name/version/etc.) so we only surface dependency-block edits.
			if (NPM_NON_DEP_KEYS.has(name.toLowerCase())) {
				return null;
			}
			// Per-line parsing can't tell a dependency block from scripts/engines/exports/resolutions/config —
			// they all share the `"key": "value"` shape. Require the value to look like a version specifier so a
			// `"build": "tsc"` line is not offered as a candidate at all.
			if (!isVersionish(constraint)) {
				return null;
			}
			return { name, constraint };
		},
	},
	{
		// Rust Cargo
		isManifest: (b) => b === "cargo.toml",
		lockfiles: ["Cargo.lock"],
		parse: parseTomlDependency,
	},
	{
		// Python requirements
		isManifest: (b) => b === "requirements.txt" || b.endsWith(".requirements.txt"),
		lockfiles: ["requirements.lock", "Pipfile.lock", "poetry.lock", "uv.lock"],
		parse: (line) => {
			const t = line.trim();
			if (t === "" || t.startsWith("#") || t.startsWith("-")) {
				return null;
			}
			// A bare `numpy` line has no constraint group at all — that absence is the PIN_DROPPED signal
			// this script exists to surface, so it becomes "" rather than dropping the dependency.
			const [, name, constraint = ""] = reReqDep.exec(t) ?? [];
			if (name === undefined) {
				return null;
			}
			return { name, constraint };
		},
	},
	{
		// Python pyproject (PEP 621 / poetry tables)
		isManifest: (b) => b === "pyproject.toml",
		lockfiles: ["poetry.lock", "uv.lock", "pdm.lock"],
		parse: parseTomlDependency,
	},
	{
		// Ruby Bundler
		isManifest: (b) => b === "gemfile",
		lockfiles: ["Gemfile.lock"],
		parse: (line) => {
			// `gem "puma"` carries no constraint group — an absent pin, not an absent dependency.
			const [, name, constraint = ""] = reGemDep.exec(line) ?? [];
			if (name === undefined) {
				return null;
			}
			return { name, constraint };
		},
	},
	{
		// Maven and XcodeGen spread one dependency over several lines — an artifactId and its version, a
		// url and its bound — and a diff carries only the lines that changed, so the unchanged half may sit
		// outside it. No added, removed or pin label is read from such a subset.
		isManifest: (b) => b === "pom.xml",
		lockfiles: [],
		parse: () => null,
		dependencyLine: /<(?:artifactId|version)>/u,
	},
	{
		// Gradle (Groovy or Kotlin DSL)
		isManifest: (b) => b === "build.gradle" || b === "build.gradle.kts",
		lockfiles: ["gradle.lockfile"],
		parse: (line) => {
			// The version group matches empty for a trailing-colon coordinate (`"g:a:"`); the coordinate
			// group cannot be empty.
			const [, coordinate, constraint = ""] = reGradleDep.exec(line) ?? [];
			if (coordinate === undefined) {
				return null;
			}
			return { name: coordinate, constraint };
		},
	},
	{
		// Swift Package Manager
		isManifest: (b) => b === "package.swift",
		lockfiles: ["Package.resolved"],
		parse: (line) => {
			// The url group is required; the three constraint forms are alternatives, so at most one of
			// them is present on any given line and the range form always yields BOTH of its bounds.
			const [, url, fromVersion, exactVersion, rangeLow, rangeHigh] = reSwiftPkg.exec(line) ?? [];
			if (url === undefined) {
				return null;
			}
			const name =
				url
					.split("/")
					.pop()
					?.replace(/\.git$/u, "") ?? url;
			// from: => caret-like (loose), exact: => exact, range => loose
			const constraint = swiftConstraint(fromVersion, exactVersion, rangeLow, rangeHigh);
			return { name, constraint };
		},
	},
	{
		// XcodeGen project.yml: a package is declared under `packages:` as a url line followed by a
		// constraint line, so it is read as Maven is.
		isManifest: (b) => b === "project.yml" || b === "project.yaml",
		lockfiles: ["Package.resolved"],
		parse: () => null,
		dependencyLine:
			/^\s*(?:url|from|exactVersion|majorVersion|minorVersion|branch|revision):\s*\S/u,
	},
	{
		// Go modules
		isManifest: (b) => b === "go.mod",
		lockfiles: ["go.sum"],
		parse: (line) => {
			// Both groups are required: a go.mod require line without a `v…` version is not matched at all.
			const [, name, version] = reGoMod.exec(line) ?? [];
			if (name === undefined || version === undefined) {
				return null;
			}
			return { name, constraint: version };
		},
	},
];

function basenameLower(path: string): string {
	return (path.split("/").pop() ?? path).toLowerCase();
}

// Escape a dependency name for safe embedding in a RegExp (names can contain ., -, /, @ and similar).
function escapeRegExp(s: string): string {
	return s.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
}

function ecosystemFor(path: string): Ecosystem | null {
	const base = basenameLower(path);
	return ECOSYSTEMS.find((e) => e.isManifest(base)) ?? null;
}

// Classify the constraint delta for a dependency present on both sides (added + removed for same name).
function classifyDelta(oldC: string, newC: string): string {
	const o = oldC.trim();
	const n = newC.trim();
	// Identical constraint text on both sides means the -X/+X pair differs only by whitespace/newline (e.g. a
	// trailing-newline reflow): there is no version change, so surface a neutral no-change fact rather than a
	// phantom version bump. UNCHANGED is informational only and is never counted toward the bump tally.
	if (o === n) {
		return "UNCHANGED";
	}
	const oExact = !isLoose(o);
	const nLoose = isLoose(n);
	if (n === "" && o !== "") {
		return "PIN_DROPPED";
	}
	if (oExact && nLoose) {
		return "PIN_LOOSENED";
	}
	return "BUMPED";
}

// Collect { name -> constraint } from added/removed manifest lines for a single ecosystem file.
function collectDeps(df: DiffFile, eco: Ecosystem, side: "added" | "removed"): Map<string, string> {
	const out = new Map<string, string>();
	const lines = side === "added" ? df.addedLines : df.removedLines;
	for (const [, content] of lines) {
		const parsed = eco.parse(content);
		if (parsed) {
			out.set(parsed.name, parsed.constraint);
		}
	}
	return out;
}

/** How many captured paths a direction names before it only counts the rest. */
const PATHS_NAMED = 5;

function namedPaths(paths: readonly string[]): string {
	const named = paths.slice(0, PATHS_NAMED).join(", ");
	return paths.length > PATHS_NAMED ? `${named} and ${paths.length - PATHS_NAMED} more` : named;
}

/** The hint's context line: the matched name and constraint on its side, or the delta between the two. */
function dependencyContext(fact: string, name: string, oldC: string, newC: string): string {
	if (fact === "ONLY_ADDED") {
		return `+ ${name} ${newC}`.trim();
	}
	return fact === "ONLY_REMOVED" ? `- ${name} ${oldC}`.trim() : `${name}: ${oldC} -> ${newC}`;
}

export default function changesDependenciesDeliberately(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const changedManifests = new Set<string>();
	const touchedLockfiles = new Set<string>();
	let onlyAdded = 0;
	let onlyRemoved = 0;
	let pinsLoosened = 0;
	let pinsDropped = 0;
	let bumped = 0;
	let unpairedManifestLines = 0;

	// Where the checkout holds a file with a lockfile's name. A path anywhere in the repository is not
	// evidence that it belongs to a changed manifest; the paths are reported for the review to relate.
	const allLockfileNames = new Set(
		ECOSYSTEMS.flatMap((e) => e.lockfiles.map((l) => l.toLowerCase())),
	);
	const checkoutLockfiles: string[] = [];
	for (const ext of ["json", "lock", "yaml", "resolved", "lockfile", "sum"]) {
		for (const f of globFilesSync(`**/*.${ext}`, repoPath)) {
			if (allLockfileNames.has(basenameLower(f))) {
				checkoutLockfiles.push(f);
			}
		}
	}

	for (const [path, df] of diffFiles) {
		const base = basenameLower(path);
		if (allLockfileNames.has(base)) {
			touchedLockfiles.add(path);
			continue;
		}
		const eco = ecosystemFor(path);
		if (!eco) {
			continue;
		}
		changedManifests.add(path);

		const { dependencyLine } = eco;
		if (dependencyLine !== undefined) {
			for (const side of ["removed", "added"] as const) {
				const lines = [...(side === "added" ? df.addedLines : df.removedLines)];
				for (const [ln, content] of lines.filter(([, c]) => dependencyLine.test(c))) {
					unpairedManifestLines += 1;
					hints.push({
						file: path,
						line: ln,
						pattern: "candidate:raw manifest line",
						context: `${side === "added" ? "+" : "-"} ${content.trim()}`.slice(0, 160),
						inDiff: true,
						flags: { ecosystem: base, side },
					});
				}
			}
			continue;
		}
		const added = collectDeps(df, eco, "added");
		const removed = collectDeps(df, eco, "removed");

		// helper to find the diff line for a dependency name on a given side (for hint placement). Match on a
		// quote/word/coordinate boundary, not a bare substring, so a prefix-sharing sibling (react vs
		// react-dom, or a scoped name appearing inside another package's URL) doesn't grab the wrong line.
		const lineFor = (name: string, side: "added" | "removed"): number => {
			const lines = side === "added" ? df.addedLines : df.removedLines;
			const bounded = new RegExp(`(?:^|[^\\w.\\-/])${escapeRegExp(name)}(?:[^\\w.\\-/]|$)`, "u");
			for (const [ln, content] of lines) {
				if (bounded.test(content)) {
					return ln;
				}
			}
			// Fallback: a constructed key (e.g. a Gradle group:name coordinate) may not survive the boundary
			// test against the raw line — keep the substring scan so the hint still lands on a real line.
			for (const [ln, content] of lines) {
				if (content.includes(name)) {
					return ln;
				}
			}
			return 0;
		};

		const allNames = new Set<string>([...added.keys(), ...removed.keys()]);
		for (const name of allNames) {
			const inAdded = added.has(name);
			const inRemoved = removed.has(name);
			let fact: string;
			let side: "added" | "removed" = "added";
			if (inAdded && !inRemoved) {
				fact = "ONLY_ADDED";
				onlyAdded += 1;
			} else if (!inAdded && inRemoved) {
				fact = "ONLY_REMOVED";
				side = "removed";
				onlyRemoved += 1;
			} else {
				fact = classifyDelta(removed.get(name) ?? "", added.get(name) ?? "");
				if (fact === "PIN_LOOSENED") {
					pinsLoosened += 1;
				} else if (fact === "PIN_DROPPED") {
					pinsDropped += 1;
				} else if (fact === "UNCHANGED") {
					// whitespace/newline-only line pair — not a version change, count nothing
				} else {
					bumped += 1;
				}
			}
			const oldC = removed.get(name) ?? "";
			const newC = added.get(name) ?? "";
			const ctx = dependencyContext(fact, name, oldC, newC);
			hints.push({
				file: path,
				line: lineFor(name, side),
				pattern: `candidate:${fact}`,
				context: ctx.slice(0, 160),
				inDiff: true,
				flags: { ecosystem: base, name },
			});
		}
	}

	const lockfilePresent = checkoutLockfiles.length > 0 || touchedLockfiles.size > 0;
	const listed = hints.slice(0, 40);
	const rawLinesListed = listed.filter((h) => h.pattern === "candidate:raw manifest line").length;

	const directions: string[] = [];
	if (changedManifests.size > 0) {
		directions.push(
			`Dependency manifest(s) changed (${changedManifests.size}). Candidate lines, matched by shape and not yet placed in a dependency section: ${onlyAdded} only on an added line, ${onlyRemoved} only on a removed line, ${pinsLoosened} pin(s) loosened, ${pinsDropped} pin(s) dropped, ${bumped} version bump(s). A metadata field such as a package.json engines entry or a Cargo rust-version has the same shape, and a name on one side only may be a moved or reformatted line rather than a new or dropped dependency — read the section around each before treating it as a dependency change${unpairedManifestLines > 0 ? `; the ${unpairedManifestLines} raw multi-line manifest line(s) are not in these counts, so a zero here does not show that nothing changed` : ""}. Then investigate whether each dependency constraint change is deliberate and appropriately bounded.`,
		);
		if (unpairedManifestLines > 0) {
			directions.push(
				`${unpairedManifestLines} changed dependency line(s) in a pom.xml or XcodeGen project.yml carry no added, removed or pin label${rawLinesListed < unpairedManifestLines ? `, and only ${rawLinesListed} of them are listed as raw lines here — read the manifest diffs for the other ${unpairedManifestLines - rawLinesListed}` : " and are listed as raw lines"}: these manifests spread one dependency over several lines and the diff shows only the changed ones. Read the manifest around each to tell an addition from a version change or a reordering; a pom.xml coordinate may also be a build plugin or a parent/BOM rather than a dependency.`,
			);
		}
		if (lockfilePresent) {
			directions.push(
				`Lockfile-named file(s) in the checkout: ${checkoutLockfiles.length === 0 ? "none" : namedPaths(checkoutLockfiles)}; touched in this diff: ${touchedLockfiles.size === 0 ? "none" : namedPaths([...touchedLockfiles])}. A lockfile answers for a changed manifest when the ecosystem resolves that manifest through it — beside it, or at a parent workspace root whose configuration includes it — so read the paths and that configuration before expecting a matching update.`,
			);
		} else {
			directions.push(
				"No file with a lockfile name these ecosystems write was found in the checkout or touched in the diff — investigate whether the ecosystem expects a committed lockfile to make the resolved versions reproducible.",
			);
		}
	}

	return {
		hints: listed,
		metrics: {
			manifestsChanged: changedManifests.size,
			onlyAdded,
			onlyRemoved,
			pinsLoosened,
			pinsDropped,
			bumped,
			unpairedManifestLines,
			rawLinesListed,
			lockfilesTouched: touchedLockfiles.size,
			lockfilePresent: lockfilePresent ? 1 : 0,
		},
		directions,
	};
}
