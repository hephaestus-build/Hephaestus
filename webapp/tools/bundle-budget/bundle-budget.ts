import { z } from "zod";

/**
 * A chunk of the build metadata that `tools/bundle-packages.ts` writes from the final emitted
 * JavaScript. The supply-chain guide owns its schema; the budget reads only these fields.
 */
const builtChunk = z.object({
	fileName: z.string().min(1),
	isEntry: z.boolean(),
	/** Loaded through `import()`: a route's split component or a lazy component. */
	isDynamicEntry: z.boolean(),
	/** The module the chunk stands for, relative to `webapp/` with its query, or null for a shared chunk. */
	facadeModule: z.string().min(1).nullable(),
	/** Chunks this one imports statically: the browser fetches them before the chunk runs. */
	imports: z.array(z.string()),
	/** The final code compressed as nginx serves it (`gzip_comp_level 6`). */
	gzipBytes: z.number().int().nonnegative(),
	packages: z.array(z.string()),
});

export const bundleMetadata = z.object({
	schemaVersion: z.literal(1),
	chunks: z.array(builtChunk),
});

export type BuiltChunk = z.infer<typeof builtChunk>;

/** The gzip bytes that each load fetches. */
export interface BundleSizes {
	/** A cold load of any page: the entry chunk and every chunk it imports statically. */
	entry: number;
	/** What each lazy chunk adds when it loads, by its facade module: its static closure past the entry's. */
	lazy: Record<string, number>;
}

export interface Measured {
	sizes: BundleSizes;
	/** The packages in the entry closure. */
	entryPackages: string[];
}

/** Growth that one change may add to a size: a small feature or fix fits in it. */
export const TOLERANCE_BYTES = 1024;

/** The chunks a load fetches before `start` runs: `start` and its static imports, transitively. */
function staticClosure(start: string, byName: ReadonlyMap<string, BuiltChunk>): Set<string> {
	const seen = new Set<string>();
	const pending = [start];
	for (let name = pending.pop(); name !== undefined; name = pending.pop()) {
		if (!seen.has(name)) {
			seen.add(name);
			pending.push(...(byName.get(name)?.imports ?? []));
		}
	}
	return seen;
}

/**
 * A lazy chunk's key: its facade module. A dependency's install path names its version, so a key
 * there starts at the package, and a version bump keeps the key.
 */
export function lazyKey(facadeModule: string): string {
	const installed = "/node_modules/";
	const at = facadeModule.lastIndexOf(installed);
	return at === -1 ? facadeModule : facadeModule.slice(at + installed.length);
}

function gzipOf(names: Iterable<string>, byName: ReadonlyMap<string, BuiltChunk>): number {
	return [...names].reduce((sum, name) => sum + (byName.get(name)?.gzipBytes ?? 0), 0);
}

export function measure(chunks: readonly BuiltChunk[]): Measured {
	const byName = new Map(chunks.map((chunk) => [chunk.fileName, chunk]));
	const entries = chunks.filter((chunk) => chunk.isEntry);
	if (entries.length !== 1) {
		throw new Error(`Expected one entry chunk, found ${entries.length}`);
	}
	const entryClosure = staticClosure(entries[0]?.fileName ?? "", byName);
	const lazy: Record<string, number> = {};
	for (const chunk of chunks) {
		if (!chunk.isDynamicEntry || chunk.facadeModule === null) {
			continue;
		}
		const key = lazyKey(chunk.facadeModule);
		if (key in lazy) {
			throw new Error(`Two lazy chunks stand for ${key}`);
		}
		const added = [...staticClosure(chunk.fileName, byName)].filter(
			(name) => !entryClosure.has(name),
		);
		lazy[key] = gzipOf(added, byName);
	}
	const entryPackages = new Set(
		[...entryClosure].flatMap((name) => byName.get(name)?.packages ?? []),
	);
	return {
		sizes: {
			entry: gzipOf(entryClosure, byName),
			lazy: Object.fromEntries(Object.entries(lazy).sort(([a], [b]) => a.localeCompare(b))),
		},
		entryPackages: [...entryPackages].sort(),
	};
}

/** Why the entry closure holds a package that only some routes use: it then loads with every page. */
export function entryViolations(
	measured: Measured,
	routeOnlyPackages: readonly string[],
): string[] {
	return routeOnlyPackages
		.filter((name) => measured.entryPackages.includes(name))
		.map((name) => `${name} is in the entry closure; only the routes that use it may load it.`);
}

/** One load that a change grew past the tolerance on purpose, and the change that accepted it. */
const acceptance = z.object({
	/** `entry`, or a lazy chunk's facade module. */
	load: z.string().min(1),
	gzipBytes: z.number().int().nonnegative(),
	/** The pull request, or the branch before it has one. */
	acceptedBy: z.string().min(1),
	/** Why the growth is worth it. Update mode requires it; acceptances recorded before it have none. */
	reason: z.string().min(1).optional(),
});

/** `accepted-growth.json`: the acceptances of the change that commits it. */
export const acceptedGrowth = z.object({ accepted: z.array(acceptance) });

export type Acceptance = z.infer<typeof acceptance>;

const sameAcceptance = (a: Acceptance, b: Acceptance) =>
	a.load === b.load && a.gzipBytes === b.gzipBytes && a.acceptedBy === b.acceptedBy;

/**
 * The acceptances that this change adds. One that the base already holds was spent by the change
 * that added it: its growth is in the base build, so it grants nothing more.
 */
export function ownAcceptances(
	head: readonly Acceptance[],
	base: readonly Acceptance[],
): Acceptance[] {
	return head.filter((entry) => !base.some((spent) => sameAcceptance(entry, spent)));
}

/** Every load of a build: the entry first, then each lazy chunk by its facade module. */
function loads(sizes: BundleSizes): [string, number][] {
	return [["entry", sizes.entry], ...Object.entries(sizes.lazy)];
}

function sizeOf(sizes: BundleSizes, load: string): number | undefined {
	return load === "entry" ? sizes.entry : sizes.lazy[load];
}

/** The loads that this change grew by more than the tolerance against the build it merges onto. */
function grownLoads(head: BundleSizes, base: BundleSizes): [string, number][] {
	return loads(head).filter(([load, now]) => now > (sizeOf(base, load) ?? 0) + TOLERANCE_BYTES);
}

/**
 * Why the change breaks the budget: each load that it grew past the tolerance against the build of
 * the commit it merges onto, and that none of its own acceptances covers, and a lazy chunk that moved
 * into a grown entry. Growth that landed earlier is in the base build, so it never counts against a
 * later change.
 */
export function growthViolations(
	head: BundleSizes,
	base: BundleSizes,
	accepted: readonly Acceptance[],
): string[] {
	const grown = grownLoads(head, base).filter(
		([load, now]) =>
			!accepted.some((entry) => entry.load === load && now <= entry.gzipBytes + TOLERANCE_BYTES),
	);
	const violations = grown.map(([load, now]) => {
		const then = sizeOf(base, load);
		let what = load;
		if (load === "entry") {
			what = "The entry closure";
		} else if (then === undefined) {
			what = `${load}, a new lazy chunk,`;
		}
		const hint =
			load === "entry"
				? " Find the route option (validateSearch, beforeLoad, loader, pendingComponent) that imports a component module."
				: "";
		return `${what} grew from ${then ?? 0} to ${now} gzip bytes.${hint}`;
	});
	// The build metadata does not say which modules a chunk holds, so a lazy chunk that is gone counts as
	// moved into the entry when the entry grew past the tolerance.
	if (grown.some(([load]) => load === "entry")) {
		for (const facade of Object.keys(base.lazy).filter((key) => !(key in head.lazy))) {
			violations.push(
				`${facade} is no longer a lazy chunk, and the entry grew: it may load with every page now.`,
			);
		}
	}
	return violations;
}

/** This change's acceptances: one for each load that it grew past the tolerance. */
export function acceptGrowth(
	head: BundleSizes,
	base: BundleSizes,
	acceptedBy: string,
	reason: string,
): Acceptance[] {
	return grownLoads(head, base).map(([load, gzipBytes]) => ({
		load,
		gzipBytes,
		acceptedBy,
		reason,
	}));
}
