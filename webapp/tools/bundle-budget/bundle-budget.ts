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
export const bundleSizes = z.object({
	/** A cold load of any page: the entry chunk and every chunk it imports statically. */
	entry: z.number().int().nonnegative(),
	/** What each lazy chunk adds when it loads, by its facade module: its static closure past the entry's. */
	lazy: z.record(z.string(), z.number().int().nonnegative()),
});

export type BundleSizes = z.infer<typeof bundleSizes>;

export interface Measured {
	sizes: BundleSizes;
	/** The packages in the entry closure. */
	entryPackages: string[];
}

/** Growth up to this keeps the baseline: a small feature or fix fits in it. */
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

export interface BudgetCheck {
	/** Why the build breaks its budget. */
	violations: string[];
	/** Savings the baseline still counts as bytes: `update` lowers it. */
	lowerable: string[];
}

/**
 * A one-way ratchet. Growth past the tolerance, a route-only package in the entry, or a lazy chunk
 * that moved into the entry breaks the budget. A saving passes and only asks for the baseline to come
 * down, so a pull request that shrinks the bundle needs no baseline commit and does not have to merge
 * after another one that changes it. The next `update` takes the saving.
 *
 * The build metadata does not say which modules a chunk holds, so a lazy chunk that is gone counts as
 * moved into the entry when the entry grew past the tolerance, and as removed otherwise.
 */
export function checkBudget(
	measured: Measured,
	baseline: BundleSizes,
	routeOnlyPackages: readonly string[],
): BudgetCheck {
	const violations: string[] = [];
	const lowerable: string[] = [];
	const compare = (what: string, now: number, then: number, hint = "") => {
		if (now > then + TOLERANCE_BYTES) {
			violations.push(`${what} grew from ${then} to ${now} gzip bytes.${hint}`);
		} else if (now < then - TOLERANCE_BYTES) {
			lowerable.push(`${what} shrank from ${then} to ${now} gzip bytes.`);
		}
	};
	const entryGrew = measured.sizes.entry > baseline.entry + TOLERANCE_BYTES;
	compare(
		"The entry closure",
		measured.sizes.entry,
		baseline.entry,
		" Find the route option (validateSearch, beforeLoad, loader, pendingComponent) that imports a component module.",
	);
	for (const [facade, now] of Object.entries(measured.sizes.lazy)) {
		compare(
			baseline.lazy[facade] === undefined ? `${facade}, a new lazy chunk,` : facade,
			now,
			baseline.lazy[facade] ?? 0,
		);
	}
	for (const facade of Object.keys(baseline.lazy)) {
		if (facade in measured.sizes.lazy) {
			continue;
		}
		if (entryGrew) {
			violations.push(
				`${facade} is no longer a lazy chunk, and the entry grew: it may load with every page now.`,
			);
		} else {
			lowerable.push(`${facade} is no longer a lazy chunk.`);
		}
	}
	for (const name of routeOnlyPackages.filter((pkg) => measured.entryPackages.includes(pkg))) {
		violations.push(`${name} is in the entry closure; only the routes that use it may load it.`);
	}
	return { violations, lowerable };
}
