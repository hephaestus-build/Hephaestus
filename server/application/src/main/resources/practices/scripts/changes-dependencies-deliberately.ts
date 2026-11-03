import {
	definePracticeScript,
	type PreparedContext,
	type ReviewLead,
} from "@hephaestus/practice-scripts";
import { scm, type DiffFile, type FileTree } from "@hephaestus/practice-scripts/scm";

export const LABELS = {
	"only-added":
		"A dependency name that matches only on an added line of a manifest: new, or moved or reformatted.",
	"only-removed":
		"A dependency name that matches only on a removed line of a manifest: dropped, or moved or reformatted.",
	"pin-loosened":
		"A dependency whose exact or narrower constraint became a range, a caret or a tilde.",
	"pin-dropped": "A dependency whose version constraint was present and is now absent.",
	bumped: "A dependency whose constraint keeps its shape with a different version.",
	unchanged:
		"A dependency line pair whose constraint text did not change, such as a reflowed line.",
	"raw-manifest-line":
		"A changed manifest line; read its dependency map and surrounding context before classifying it.",
};
type DependencyKind = keyof typeof LABELS;

interface Ecosystem {
	isManifest: (base: string) => boolean;
	lockfiles: string[];
	parse: (line: string) => { name: string; constraint: string } | null;
	dependencyLine?: RegExp;
}

const LOOSE = /(?:^$|[\^~*]|>=|<=|>|<|\.x\b|\bx\b|\|\||\s-\s|,)/u;
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

const DEPENDENCY_MAPS = [
	"dependencies",
	"devDependencies",
	"peerDependencies",
	"optionalDependencies",
] as const;

/** The dependency maps of a package.json, or null when a map is not a map of strings. */
function packageDependencies(value: unknown): Record<string, Record<string, string>> | null {
	if (typeof value !== "object" || value === null) {
		return null;
	}
	const fields = new Map(Object.entries(value));
	const maps: Record<string, Record<string, string>> = {};
	for (const name of DEPENDENCY_MAPS) {
		const map: unknown = fields.get(name);
		if (map === undefined) {
			continue;
		}
		if (typeof map !== "object" || map === null || Array.isArray(map)) {
			return null;
		}
		const versions: Record<string, string> = {};
		for (const [dependency, version] of Object.entries(map)) {
			if (dependency === "" || typeof version !== "string") {
				return null;
			}
			versions[dependency] = version;
		}
		maps[name] = versions;
	}
	return maps;
}

const reTomlDep =
	/^\s*(?<name>[A-Za-z0-9_.-]+)\s*=\s*(?:"(?<quoted>[^"]*)"|\{[^}]*version\s*=\s*"(?<table>[^"]*)"[^}]*\})/u;
const reReqDep = /^\s*(?<name>[A-Za-z0-9_.\-[\]]+)\s*(?<constraint>(?:[<>=!~]=?|@)\S.*)?$/u;
const reGemDep = /^\s*gem\s+["'](?<name>[^"']+)["']\s*(?:,\s*["'](?<constraint>[^"']*)["'])?/u;
const reGradleDep = /["'](?<coordinate>[\w.-]+:[\w.-]+):(?<constraint>[^"']*)["']/u;
const reSwiftPkg =
	/\.package\(\s*url:\s*["'](?<url>[^"']+)["'][^)]*?(?:from:\s*["'](?<from>[^"']+)["']|exact:\s*["'](?<exact>[^"']+)["']|["'](?<low>[^"']+)["']\s*\.\.[.<]\s*["'](?<high>[^"']+)["'])/u;
const reGoMod =
	/^\s*(?:require\s+)?(?<name>[\w./-]+\.[\w./-]+\/\S+|[\w.-]+\/\S+)\s+(?<version>v\d\S*)/u;

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
		parse: () => null,
	},
	{
		isManifest: (b) => b === "cargo.toml",
		lockfiles: ["Cargo.lock"],
		parse: parseTomlDependency,
	},
	{
		isManifest: (b) => b === "requirements.txt" || b.endsWith(".requirements.txt"),
		lockfiles: ["requirements.lock", "Pipfile.lock", "poetry.lock", "uv.lock"],
		parse: (line) => {
			const t = line.trim();
			if (t === "" || t.startsWith("#") || t.startsWith("-")) {
				return null;
			}
			// A bare requirements entry is a dependency without a pin, not an absent dependency.
			const [, name, constraint = ""] = reReqDep.exec(t) ?? [];
			if (name === undefined) {
				return null;
			}
			return { name, constraint };
		},
	},
	{
		isManifest: (b) => b === "pyproject.toml",
		lockfiles: ["poetry.lock", "uv.lock", "pdm.lock"],
		parse: parseTomlDependency,
	},
	{
		isManifest: (b) => b === "gemfile",
		lockfiles: ["Gemfile.lock"],
		parse: (line) => {
			// A Gemfile entry without a version is an unpinned dependency.
			const [, name, constraint = ""] = reGemDep.exec(line) ?? [];
			if (name === undefined) {
				return null;
			}
			return { name, constraint };
		},
	},
	{
		isManifest: (b) => b === "pom.xml",
		lockfiles: [],
		parse: () => null,
		dependencyLine: /<(?:artifactId|version)>/u,
	},
	{
		isManifest: (b) => b === "build.gradle" || b === "build.gradle.kts",
		lockfiles: ["gradle.lockfile"],
		parse: (line) => {
			const [, coordinate, constraint = ""] = reGradleDep.exec(line) ?? [];
			if (coordinate === undefined) {
				return null;
			}
			return { name: coordinate, constraint };
		},
	},
	{
		isManifest: (b) => b === "package.swift",
		lockfiles: ["Package.resolved"],
		parse: (line) => {
			const [, url, fromVersion, exactVersion, rangeLow, rangeHigh] = reSwiftPkg.exec(line) ?? [];
			if (url === undefined) {
				return null;
			}
			const name =
				url
					.split("/")
					.pop()
					?.replace(/\.git$/u, "") ?? url;
			const constraint = swiftConstraint(fromVersion, exactVersion, rangeLow, rangeHigh);
			return { name, constraint };
		},
	},
	{
		isManifest: (b) => b === "project.yml" || b === "project.yaml",
		lockfiles: ["Package.resolved"],
		parse: () => null,
		dependencyLine: /^\s*(?:url|from|exactVersion|majorVersion|minorVersion|branch|commit):\s*\S/u,
	},
	{
		isManifest: (b) => b === "go.mod",
		lockfiles: ["go.sum"],
		parse: (line) => {
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

function escapeRegExp(s: string): string {
	return s.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
}

function ecosystemFor(path: string): Ecosystem | null {
	const base = basenameLower(path);
	return ECOSYSTEMS.find((e) => e.isManifest(base)) ?? null;
}

function classifyDelta(oldC: string, newC: string): DependencyKind {
	const o = oldC.trim();
	const n = newC.trim();
	if (o === n) {
		return "unchanged";
	}
	const oExact = !isLoose(o);
	const nLoose = isLoose(n);
	if (n === "" && o !== "") {
		return "pin-dropped";
	}
	if (oExact && nLoose) {
		return "pin-loosened";
	}
	return "bumped";
}

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

async function capturedPackageMaps(tree: FileTree | null, path: string) {
	const captured = await tree?.read(path);
	if (captured?.status !== "FOUND" || captured.truncated) {
		return null;
	}
	try {
		return packageDependencies(JSON.parse(captured.text));
	} catch {
		return null;
	}
}

async function packageManifestInputs(path: string, df: DiffFile, tree: FileTree | null) {
	const parsed = await capturedPackageMaps(tree, path);
	const context: PreparedContext[] =
		parsed === null
			? []
			: [{ label: `Dependency maps in ${path} at the pinned head`, value: parsed }];
	const notes =
		context.length === 0
			? [
					`Dependency maps for ${path} could not be decoded from a complete read. Inspect the captured JSON and diff; never infer an absent dependency from this limit.`,
				]
			: [];
	const leads: ReviewLead[] = (
		[
			["OLD", df.removedLines],
			["NEW", df.addedLines],
		] as const
	).flatMap(([side, lines]) =>
		[...lines.keys()].map((line) => ({
			at: { change: path, line, side },
			label: LABELS["raw-manifest-line"],
			facts: { ecosystem: "package.json", side },
		})),
	);
	return { context, notes, leads };
}

const PATHS_NAMED = 5;

function namedPaths(paths: readonly string[]): string {
	const named = paths.slice(0, PATHS_NAMED).join(", ");
	return paths.length > PATHS_NAMED ? `${named} and ${paths.length - PATHS_NAMED} more` : named;
}

async function checkoutPaths(
	tree: FileTree | null,
	names: ReadonlySet<string>,
): Promise<{ paths: string[]; complete: boolean }> {
	if (tree === null) {
		return { paths: [], complete: false };
	}
	const found: string[] = [];
	let complete = true;
	for (const ext of ["json", "lock", "yaml", "resolved", "lockfile", "sum"]) {
		const { paths, truncated } = await tree.list(`**/*.${ext}`);
		found.push(...paths.filter((f) => names.has(basenameLower(f))));
		complete &&= !truncated;
	}
	return { paths: found, complete };
}

function lockfileDirections(
	checkout: { paths: string[]; complete: boolean },
	touched: ReadonlySet<string>,
): string[] {
	if (checkout.paths.length > 0 || touched.size > 0) {
		return [
			`Lockfile-named file(s) in the checkout: ${checkout.paths.length === 0 ? "none" : namedPaths(checkout.paths)}; touched in this diff: ${touched.size === 0 ? "none" : namedPaths([...touched])}.`,
			"A lockfile answers for a changed manifest when the ecosystem resolves that manifest through it: beside it, or at a parent workspace root whose configuration includes it.",
			"Read the paths and that configuration before expecting a matching update.",
		];
	}
	return checkout.complete
		? [
				"No file with a lockfile name these ecosystems write was found in the checkout or touched in the diff — investigate whether the ecosystem expects a committed lockfile to make the resolved versions reproducible.",
			]
		: [
				"No lockfile is touched in the diff. The checkout was not captured whole, so whether it holds a lockfile is unknown — read the repository before expecting one.",
			];
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const tree = work.head;
		const change = work.changedFiles();
		const leads: ReviewLead[] = [];
		const packageContexts: PreparedContext[] = [];
		const packageNotes: string[] = [];
		const changedManifests = new Set<string>();
		const touchedLockfiles = new Set<string>();
		let onlyAdded = 0;
		let onlyRemoved = 0;
		let pinsLoosened = 0;
		let pinsDropped = 0;
		let bumped = 0;
		let unpairedManifestLines = 0;

		// A lockfile elsewhere in the checkout does not prove it belongs to this manifest.
		const allLockfileNames = new Set(
			ECOSYSTEMS.flatMap((e) => e.lockfiles.map((l) => l.toLowerCase())),
		);
		const checkout = await checkoutPaths(tree, allLockfileNames);

		for (const [path, df] of change) {
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

			if (base === "package.json") {
				const prepared = await packageManifestInputs(path, df, tree);
				packageContexts.push(...prepared.context);
				packageNotes.push(...prepared.notes);
				unpairedManifestLines += prepared.leads.length;
				leads.push(...prepared.leads);
				continue;
			}
			const { dependencyLine } = eco;
			if (dependencyLine !== undefined) {
				for (const side of ["removed", "added"] as const) {
					const lines = [...(side === "added" ? df.addedLines : df.removedLines)];
					for (const [ln] of lines.filter(([, c]) => dependencyLine.test(c))) {
						unpairedManifestLines += 1;
						leads.push({
							at: { change: path, line: ln, side: side === "added" ? "NEW" : "OLD" },
							label: LABELS["raw-manifest-line"],
							facts: { ecosystem: base, side },
						});
					}
				}
				continue;
			}
			const added = collectDeps(df, eco, "added");
			const removed = collectDeps(df, eco, "removed");

			const lineFor = (name: string, side: "added" | "removed"): number => {
				const lines = side === "added" ? df.addedLines : df.removedLines;
				const bounded = new RegExp(`(?:^|[^\\w.\\-/])${escapeRegExp(name)}(?:[^\\w.\\-/]|$)`, "u");
				for (const [ln, content] of lines) {
					if (bounded.test(content)) {
						return ln;
					}
				}
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
				let kind: DependencyKind;
				let side: "added" | "removed" = "added";
				if (inAdded && !inRemoved) {
					kind = "only-added";
					onlyAdded += 1;
				} else if (!inAdded && inRemoved) {
					kind = "only-removed";
					side = "removed";
					onlyRemoved += 1;
				} else {
					kind = classifyDelta(removed.get(name) ?? "", added.get(name) ?? "");
					if (kind === "pin-loosened") {
						pinsLoosened += 1;
					} else if (kind === "pin-dropped") {
						pinsDropped += 1;
					} else if (kind !== "unchanged") {
						bumped += 1;
					}
				}
				const line = lineFor(name, side);
				if (line > 0) {
					leads.push({
						at: { change: path, line, side: side === "added" ? "NEW" : "OLD" },
						label: LABELS[kind],
						facts: {
							ecosystem: base,
							name,
							oldConstraint: inAdded && inRemoved ? (removed.get(name) ?? "") : "",
							newConstraint: inAdded && inRemoved ? (added.get(name) ?? "") : "",
						},
					});
				}
			}
		}

		const lockfilePresent = checkout.paths.length > 0 || touchedLockfiles.size > 0;
		const listed = leads.slice(0, 40);
		const rawLinesListed = listed.filter(
			(lead) => lead.label === LABELS["raw-manifest-line"],
		).length;

		const directions: string[] = packageNotes.length === 0 ? [] : [packageNotes.join(" ")];
		if (changedManifests.size > 0) {
			directions.push(
				`Manifest-named file(s) changed (${changedManifests.size}). The name alone is no applicability: a changed external dependency declaration, resolution, source or pin inside one is, and internal target, test-target, scheme or build-setting wiring alone is not.`,
				`Candidate lines, matched by shape and not yet placed in a dependency section: ${onlyAdded} only on an added line, ${onlyRemoved} only on a removed line, ${pinsLoosened} pin(s) loosened, ${pinsDropped} pin(s) dropped, ${bumped} version bump(s).`,
				"A metadata field such as a package.json engines entry or a Cargo rust-version has the same shape, and a name on one side only may be a moved or reformatted line rather than a new or dropped dependency.",
				`Read the section around each before treating it as a dependency change${unpairedManifestLines > 0 ? `; the ${unpairedManifestLines} raw multi-line manifest line(s) are not in these counts, so a zero here does not show that nothing changed` : ""}. Then investigate whether each dependency constraint change is deliberate and appropriately bounded.`,
			);
			if (leads.length === 0) {
				directions.push(
					"No changed line in these manifests has the dependency shape this script reads. Read the changed sections before deciding.",
					"A declaration spread over lines can escape a per-line reading, while internal wiring such as a target dependency, a test target or a scheme entry declares no external dependency.",
				);
			}
			if (unpairedManifestLines > 0) {
				directions.push(
					`${unpairedManifestLines} changed manifest line(s) in package.json, pom.xml or XcodeGen project.yml carry no added, removed or pin label${rawLinesListed < unpairedManifestLines ? `, and only ${rawLinesListed} of them are listed as raw lines here — read the manifest diffs for the other ${unpairedManifestLines - rawLinesListed}` : " and are listed as raw lines"}.`,
					"These manifests spread one dependency over several lines and the diff shows only the changed ones. Read the manifest around each to tell an addition from a version change or a reordering.",
				);
			}
			directions.push(...lockfileDirections(checkout, touchedLockfiles));
		}

		return ctx.review({
			leads: listed,
			context: packageContexts,
			facts: {
				manifestsChanged: changedManifests.size,
				onlyAdded,
				onlyRemoved,
				pinsLoosened,
				pinsDropped,
				bumped,
				unpairedManifestLines,
				rawLinesListed,
				lockfilesTouched: touchedLockfiles.size,
				...(lockfilePresent || checkout.complete
					? { lockfilePresent: lockfilePresent ? 1 : 0 }
					: {}),
			},
			directions,
		});
	},
});
