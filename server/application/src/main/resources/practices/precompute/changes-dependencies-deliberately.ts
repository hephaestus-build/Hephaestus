// Precompute HINTS for changes-dependencies-deliberately. Surfaces facts only — the LLM judges whether a
// dependency edit was deliberate (intentional add/remove/bump with an appropriate constraint) or careless
// (loosened pin, dropped version, no lockfile). General by design: a per-manifest pattern table keyed off
// the file basename, NOT a single ecosystem. Adding an ecosystem = adding a row, no engine change.
//
// For each changed manifest line we pair an added line against a removed line for the SAME dependency name
// and classify the version-constraint delta as a neutral FACT:
//   ADDED         — dep appears only on a + line (new dependency)
//   REMOVED       — dep appears only on a - line (dependency dropped)
//   PIN_LOOSENED  — exact/narrower constraint became a range/caret/tilde (==1.2.3 -> >=1.2, 1.2.3 -> ^1.2.3)
//   PIN_DROPPED   — a version constraint was present and is now entirely absent
//   BUMPED        — same constraint shape, different version value
import { findFiles } from "../lib/grep.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// ecosystem key -> how to recognise its manifest + lockfile, and how to read a "name => constraint" line.
interface Ecosystem {
	// manifest basename test (lowercased)
	isManifest: (base: string) => boolean;
	// sibling lockfile basenames for this ecosystem
	lockfiles: string[];
	// extract { name, constraint } from a single manifest line, or null if the line is not a dependency.
	parse: (line: string) => { name: string; constraint: string } | null;
}

// A constraint is "loose" if it admits more than one version: range operators, caret, tilde, wildcard, or empty.
const LOOSE = /(^$|[\^~*]|>=|<=|>|<|\.x\b|\bx\b|\|\||\s-\s|,)/;
// A constraint is "exact" if it pins a single version: leading ==, =, or a bare semver-ish token.
const EXACT = /^(==?|v)?\d/;

function isLoose(c: string): boolean {
	const t = c.trim();
	if (t === "" || t === "*") return true;
	if (EXACT.test(t) && !LOOSE.test(t.replace(/^==?/, ""))) return false;
	return LOOSE.test(t);
}

// A package.json string value LOOKS like a dependency constraint (semver/range/protocol) rather than a
// script body, an `engines`/`exports`/`resolutions`/`config` value, etc. The per-line JSON parser cannot
// see which object block a line sits in, so this shape guard keeps `"build": "tsc"` or `"./dist": "..."`
// out of the dependency tally. A bare "*"/"x"/"latest" and the npm pseudo-protocols (workspace:/npm:/
// file:/link:/git/http) are real dependency specifiers and are admitted.
const VERSIONISH =
	/^(?:[\^~>=<* v]|\d|x\b|latest$|workspace:|npm:|file:|link:|git[+:]|https?:|github:|gitlab:|bitbucket:)/i;
function isVersionish(c: string): boolean {
	return VERSIONISH.test(c.trim());
}

// --- per-ecosystem line parsers (kept deliberately small + tolerant; the LLM reads full context) ---

// JSON "name": "constraint"  (package.json dependency blocks)
const reJsonDep = /^\s*"([^"]+)"\s*:\s*"([^"]*)"\s*,?\s*$/;
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
const reTomlDep = /^\s*([A-Za-z0-9_.-]+)\s*=\s*(?:"([^"]*)"|\{[^}]*version\s*=\s*"([^"]*)"[^}]*\})/;
// requirements.txt  name==1.2.3 / name>=1,<2 / name
const reReqDep = /^\s*([A-Za-z0-9_.\-[\]]+)\s*((?:[<>=!~]=?|@)\S.*)?$/;
// Gemfile  gem "name", "~> 1.2"
const reGemDep = /^\s*gem\s+["']([^"']+)["']\s*(?:,\s*["']([^"']*)["'])?/;
// Maven pom.xml  <artifactId>name</artifactId> ... we approximate per-line on artifactId/version pairs.
const reMvnArtifact = /<artifactId>\s*([^<\s]+)\s*<\/artifactId>/;
const reMvnVersion = /<version>\s*([^<\s]+)\s*<\/version>/;
// Gradle  implementation("group:name:1.2.3")  or  implementation 'group:name:1.2.3'
const reGradleDep = /["']([\w.-]+:[\w.-]+):([^"']*)["']/;
// Swift PM  .package(url: "...", from: "1.2.3") / exact: "1.2.3" / "1.0.0"..."2.0.0"
const reSwiftPkg =
	/\.package\(\s*url:\s*["']([^"']+)["'][^)]*?(?:from:\s*["']([^"']+)["']|exact:\s*["']([^"']+)["']|["']([^"']+)["']\s*\.\.[.<]\s*["']([^"']+)["'])/;
// go.mod  require module v1.2.3  (single-line or block-body line)
const reGoMod = /^\s*(?:require\s+)?([\w./-]+\.[\w./-]+\/\S+|[\w.-]+\/\S+)\s+(v\d\S*)/;

// Cargo.toml and pyproject.toml read dependency lines identically. The two version groups are the two
// arms of an alternation — `name = "1.2"` fills the first, `name = { version = "1.2" }` the second, and
// an inline table with no version key (`name = { features = [...] }`) fills neither.
function parseTomlDependency(line: string): { name: string; constraint: string } | null {
	const [, name, quotedVersion, tableVersion] = reTomlDep.exec(line) ?? [];
	if (name === undefined) return null;
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
			if (name === undefined) return null;
			// Skip the package's own scalar fields (name/version/etc.) so we only surface dependency-block edits.
			if (NPM_NON_DEP_KEYS.has(name.toLowerCase())) return null;
			// Per-line parsing can't tell a dependency block from scripts/engines/exports/resolutions/config —
			// they all share the `"key": "value"` shape. Require the value to look like a version specifier so a
			// `"build": "tsc"` line is not misread as `dep:ADDED build tsc`.
			if (!isVersionish(constraint)) return null;
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
			if (t === "" || t.startsWith("#") || t.startsWith("-")) return null;
			// A bare `numpy` line has no constraint group at all — that absence is the PIN_DROPPED signal
			// this script exists to surface, so it becomes "" rather than dropping the dependency.
			const [, name, constraint = ""] = reReqDep.exec(t) ?? [];
			if (name === undefined) return null;
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
			if (name === undefined) return null;
			return { name, constraint };
		},
	},
	{
		// Maven — paired artifactId/version handled in the manifest loop, parse() unused for pairing.
		isManifest: (b) => b === "pom.xml",
		lockfiles: [],
		parse: () => null,
	},
	{
		// Gradle (Groovy or Kotlin DSL)
		isManifest: (b) => b === "build.gradle" || b === "build.gradle.kts",
		lockfiles: ["gradle.lockfile"],
		parse: (line) => {
			// The version group matches empty for a trailing-colon coordinate (`"g:a:"`); the coordinate
			// group cannot be empty.
			const [, coordinate, constraint = ""] = reGradleDep.exec(line) ?? [];
			if (coordinate === undefined) return null;
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
			if (url === undefined) return null;
			const name =
				url
					.split("/")
					.pop()
					?.replace(/\.git$/, "") ?? url;
			// from: => caret-like (loose), exact: => exact, range => loose
			const constraint = fromVersion
				? `from:${fromVersion}`
				: exactVersion
					? `exact:${exactVersion}`
					: rangeLow && rangeHigh
						? `${rangeLow}..${rangeHigh}`
						: "";
			return { name, constraint };
		},
	},
	{
		// XcodeGen project.yml: a package is declared under `packages:` as a url line followed by a
		// constraint line; the pairing across lines is done in collectXcodeGenDeps, so parse() is
		// unused here as it is for Maven.
		isManifest: (b) => b === "project.yml" || b === "project.yaml",
		lockfiles: ["Package.resolved"],
		parse: () => null,
	},
	{
		// Go modules
		isManifest: (b) => b === "go.mod",
		lockfiles: ["go.sum"],
		parse: (line) => {
			// Both groups are required: a go.mod require line without a `v…` version is not matched at all.
			const [, name, version] = reGoMod.exec(line) ?? [];
			if (name === undefined || version === undefined) return null;
			return { name, constraint: version };
		},
	},
];

function basenameLower(path: string): string {
	return (path.split("/").pop() ?? path).toLowerCase();
}

// Escape a dependency name for safe embedding in a RegExp (names can contain ., -, /, @ and similar).
function escapeRegExp(s: string): string {
	return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
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
	if (o === n) return "UNCHANGED";
	const oExact = !isLoose(o);
	const nLoose = isLoose(n);
	if (n === "" && o !== "") return "PIN_DROPPED";
	if (oExact && nLoose) return "PIN_LOOSENED";
	return "BUMPED";
}

// Collect { name -> constraint } from added/removed manifest lines for a single ecosystem file.
function collectDeps(df: DiffFile, eco: Ecosystem, side: "added" | "removed"): Map<string, string> {
	const out = new Map<string, string>();
	const lines = side === "added" ? df.addedLines : df.removedLines;
	for (const [, content] of lines) {
		const parsed = eco.parse(content);
		if (parsed) out.set(parsed.name, parsed.constraint);
	}
	return out;
}

// Maven needs pairing of <artifactId>/<version> across adjacent lines; handle it on its own. A <version>
// only pairs with an <artifactId> within MVN_PAIR_WINDOW lines — a non-adjacent version belongs to a
// different coordinate and must not be stitched onto the wrong artifactId (which would forge a PIN_DROPPED
// when the artifact's real version sits further down).
const MVN_PAIR_WINDOW = 2;
function collectMavenDeps(df: DiffFile, side: "added" | "removed"): Map<string, string> {
	const out = new Map<string, string>();
	const lines = side === "added" ? df.addedLines : df.removedLines;
	const ordered = [...lines.entries()].toSorted((a, b) => a[0] - b[0]);
	let pendingName: string | null = null;
	let pendingLine = 0;
	for (const [ln, content] of ordered) {
		const [, artifactId] = reMvnArtifact.exec(content) ?? [];
		if (artifactId !== undefined) {
			pendingName = artifactId;
			pendingLine = ln;
			out.set(artifactId, ""); // record artifact even if no adjacent version line appears
			continue;
		}
		const [, version] = reMvnVersion.exec(content) ?? [];
		if (version !== undefined && pendingName !== null && ln - pendingLine <= MVN_PAIR_WINDOW) {
			out.set(pendingName, version);
			pendingName = null;
		}
	}
	return out;
}

// XcodeGen pairs a `url:` line with the constraint line that follows it (`from:`, `exactVersion:`,
// `majorVersion:`, `minorVersion:`, `branch:`, `revision:`), within the same window Maven uses.
const reXcodeGenUrl = /^\s*url:\s*(\S+)/;
const reXcodeGenBound =
	/^\s*(from|exactVersion|majorVersion|minorVersion|branch|revision):\s*(\S+)/;
function collectXcodeGenDeps(df: DiffFile, side: "added" | "removed"): Map<string, string> {
	const out = new Map<string, string>();
	const lines = side === "added" ? df.addedLines : df.removedLines;
	const ordered = [...lines.entries()].toSorted((a, b) => a[0] - b[0]);
	let pendingName: string | null = null;
	let pendingLine = 0;
	for (const [ln, content] of ordered) {
		const [, url] = reXcodeGenUrl.exec(content) ?? [];
		if (url !== undefined) {
			pendingName =
				url
					.split("/")
					.pop()
					?.replace(/\.git$/, "") ?? url;
			pendingLine = ln;
			out.set(pendingName, "");
			continue;
		}
		const [, kind, bound] = reXcodeGenBound.exec(content) ?? [];
		if (
			kind !== undefined &&
			bound !== undefined &&
			pendingName !== null &&
			ln - pendingLine <= MVN_PAIR_WINDOW
		) {
			out.set(pendingName, `${kind}:${bound}`);
			pendingName = null;
		}
	}
	return out;
}

export default function changesDependenciesDeliberately(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const changedManifests = new Set<string>();
	const touchedLockfiles = new Set<string>();
	let depsAdded = 0;
	let depsRemoved = 0;
	let pinsLoosened = 0;
	let pinsDropped = 0;
	let bumped = 0;

	// Which lockfile basenames exist anywhere in the repo (sibling-present fact)?
	const allLockfileNames = new Set(
		ECOSYSTEMS.flatMap((e) => e.lockfiles.map((l) => l.toLowerCase())),
	);
	const repoLockfilesPresent = new Set<string>();
	// findFiles needs an extension; scan the basenames we care about via their extensions.
	for (const ext of ["json", "lock", "yaml", "resolved", "lockfile", "sum"]) {
		for (const f of findFiles(repoPath, ext)) {
			const base = basenameLower(f);
			if (allLockfileNames.has(base)) repoLockfilesPresent.add(base);
		}
	}

	for (const [path, df] of diffFiles) {
		const base = basenameLower(path);
		if (allLockfileNames.has(base)) {
			touchedLockfiles.add(base);
			continue;
		}
		const eco = ecosystemFor(path);
		if (!eco) continue;
		changedManifests.add(path);

		const isMaven = base === "pom.xml";
		const isXcodeGen = base === "project.yml" || base === "project.yaml";
		const collect = (side: "added" | "removed") =>
			isMaven
				? collectMavenDeps(df, side)
				: isXcodeGen
					? collectXcodeGenDeps(df, side)
					: collectDeps(df, eco, side);
		const added = collect("added");
		const removed = collect("removed");

		// helper to find the diff line for a dependency name on a given side (for hint placement). Match on a
		// quote/word/coordinate boundary, not a bare substring, so a prefix-sharing sibling (react vs
		// react-dom, or a scoped name appearing inside another package's URL) doesn't grab the wrong line.
		const lineFor = (name: string, side: "added" | "removed"): number => {
			const lines = side === "added" ? df.addedLines : df.removedLines;
			const bounded = new RegExp(`(^|[^\\w.\\-/])${escapeRegExp(name)}([^\\w.\\-/]|$)`);
			for (const [ln, content] of lines) if (bounded.test(content)) return ln;
			// Fallback: a constructed key (e.g. a Gradle group:name coordinate) may not survive the boundary
			// test against the raw line — keep the substring scan so the hint still lands on a real line.
			for (const [ln, content] of lines) if (content.includes(name)) return ln;
			return 0;
		};

		const allNames = new Set<string>([...added.keys(), ...removed.keys()]);
		for (const name of allNames) {
			const inAdded = added.has(name);
			const inRemoved = removed.has(name);
			let fact: string;
			let side: "added" | "removed" = "added";
			if (inAdded && !inRemoved) {
				fact = "ADDED";
				depsAdded++;
			} else if (!inAdded && inRemoved) {
				fact = "REMOVED";
				side = "removed";
				depsRemoved++;
			} else {
				fact = classifyDelta(removed.get(name) ?? "", added.get(name) ?? "");
				if (fact === "PIN_LOOSENED") pinsLoosened++;
				else if (fact === "PIN_DROPPED") pinsDropped++;
				else if (fact === "UNCHANGED") {
					// whitespace/newline-only line pair — not a version change, count nothing
				} else bumped++;
			}
			const oldC = removed.get(name) ?? "";
			const newC = added.get(name) ?? "";
			const ctx =
				fact === "ADDED"
					? `+ ${name} ${newC}`.trim()
					: fact === "REMOVED"
						? `- ${name} ${oldC}`.trim()
						: `${name}: ${oldC} -> ${newC}`;
			hints.push({
				file: path,
				line: lineFor(name, side),
				pattern: `dep:${fact}`,
				context: ctx.slice(0, 160),
				inDiff: true,
				flags: { ecosystem: base, dependency: name },
			});
		}
	}

	const lockfilePresent = repoLockfilesPresent.size > 0 || touchedLockfiles.size > 0;

	const mavenChanged = [...changedManifests].some((p) => basenameLower(p) === "pom.xml");

	const directions: string[] = [];
	if (changedManifests.size > 0) {
		directions.push(
			`Dependency manifest(s) changed (${changedManifests.size}): ${depsAdded} added, ${depsRemoved} removed, ${pinsLoosened} pin(s) loosened, ${pinsDropped} pin(s) dropped, ${bumped} version bump(s) — investigate whether each constraint change is deliberate and appropriately bounded.`,
		);
		if (mavenChanged) {
			directions.push(
				"pom.xml hints pair any <artifactId>/<version> on changed lines, so they may include build-plugin or parent/BOM coordinates (from <plugin>/<parent>/<dependencyManagement> blocks), not only runtime dependencies — confirm the coordinate is an actual dependency before treating it as one.",
			);
		}
		if (!lockfilePresent) {
			directions.push(
				"No sibling lockfile is present in the repo and none was touched in the diff — investigate whether the ecosystem expects a committed lockfile to make the resolved versions reproducible.",
			);
		} else if (touchedLockfiles.size === 0) {
			directions.push(
				"A lockfile exists in the repo but was not updated in this diff — investigate whether the manifest change should have a matching lockfile update.",
			);
		}
	}

	return {
		hints: hints.slice(0, 40),
		metrics: {
			manifestsChanged: changedManifests.size,
			depsAdded,
			depsRemoved,
			pinsLoosened,
			pinsDropped,
			bumped,
			lockfilesTouched: touchedLockfiles.size,
			lockfilePresent: lockfilePresent ? 1 : 0,
		},
		directions,
	};
}
