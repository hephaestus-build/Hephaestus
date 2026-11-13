import { describe, expect, it } from "vitest";

import {
	type Acceptance,
	acceptGrowth,
	type BuiltChunk,
	type BundleSizes,
	entryViolations,
	growthViolations,
	lazyKey,
	measure,
	ownAcceptances,
	TOLERANCE_BYTES,
} from "./bundle-budget";

const chunk = (overrides: Partial<BuiltChunk> & Pick<BuiltChunk, "fileName">): BuiltChunk => ({
	isEntry: false,
	isDynamicEntry: false,
	facadeModule: null,
	imports: [],
	gzipBytes: 100,
	packages: [],
	...overrides,
});

const PROFILE =
	"src/routes/_authenticated/w/$workspaceSlug/practice-profile.tsx?tsr-split=component";

const build = [
	chunk({ fileName: "index.js", isEntry: true, imports: ["shell.js"], gzipBytes: 300 }),
	chunk({ fileName: "shell.js", imports: ["index.js"], packages: ["react"] }),
	// The profile's chunk shares the shell with the entry, and adds a chart library of its own.
	chunk({
		fileName: "profile.js",
		isDynamicEntry: true,
		facadeModule: PROFILE,
		imports: ["shell.js", "charts.js"],
		gzipBytes: 50,
	}),
	chunk({ fileName: "charts.js", packages: ["recharts"], gzipBytes: 400 }),
];

describe("lazyKey", () => {
	it("keeps an application module and cuts a dependency's versioned install path", () => {
		expect(lazyKey(PROFILE)).toBe(PROFILE);
		expect(
			lazyKey(
				"../node_modules/.pnpm/monaco-editor@0.56.0/node_modules/monaco-editor/esm/vs/editor/editor.main.js",
			),
		).toBe("monaco-editor/esm/vs/editor/editor.main.js");
	});
});

describe("measure", () => {
	it("counts the entry's static closure and what each lazy chunk adds past it", () => {
		expect(measure(build)).toStrictEqual({
			sizes: { entry: 400, lazy: { [PROFILE]: 450 } },
			entryPackages: ["react"],
		});
	});

	it("refuses a build without exactly one entry", () => {
		expect(() => measure([chunk({ fileName: "a.js" })])).toThrow("one entry chunk");
	});
});

describe("entryViolations", () => {
	it("breaks on a route-only package in the entry closure", () => {
		expect(entryViolations(measure(build), ["react"])).toStrictEqual([
			"react is in the entry closure; only the routes that use it may load it.",
		]);
		expect(entryViolations(measure(build), ["recharts"])).toStrictEqual([]);
	});
});

const accept = (load: string, gzipBytes: number): Acceptance => ({
	load,
	gzipBytes,
	acceptedBy: "https://github.com/hephaestus-build/Hephaestus/pull/1",
});

describe("growthViolations", () => {
	const base: BundleSizes = { entry: 5000, lazy: { [PROFILE]: 5000 } };

	it("passes growth within the tolerance of the base build, however far the base drifted", () => {
		// Main measured this: earlier changes each grew the profile by less than the tolerance.
		const drifted: BundleSizes = { entry: 5000, lazy: { [PROFILE]: 5000 + 950 } };
		const head: BundleSizes = { entry: 5000, lazy: { [PROFILE]: 5000 + 950 + 300 } };
		expect(growthViolations(head, drifted, [])).toStrictEqual([]);
		expect(
			growthViolations({ entry: 5000 + TOLERANCE_BYTES, lazy: base.lazy }, base, []),
		).toStrictEqual([]);
	});

	it("breaks on growth past the tolerance, unless this change accepts it", () => {
		const head: BundleSizes = { entry: 5001 + TOLERANCE_BYTES, lazy: base.lazy };
		expect(growthViolations(head, base, [])).toStrictEqual([
			expect.stringContaining(`The entry closure grew from 5000 to ${5001 + TOLERANCE_BYTES}`),
		]);
		expect(growthViolations(head, base, [accept("entry", head.entry)])).toStrictEqual([]);
		// An acceptance covers the size it records, not any growth of its load.
		expect(
			growthViolations({ ...head, entry: head.entry + TOLERANCE_BYTES + 1 }, base, [
				accept("entry", head.entry),
			]),
		).toHaveLength(1);
	});

	it("counts a new lazy chunk as growth from nothing", () => {
		const head: BundleSizes = {
			entry: 5000,
			lazy: { ...base.lazy, "new.tsx": TOLERANCE_BYTES + 1 },
		};
		expect(growthViolations(head, base, [])).toStrictEqual([
			`new.tsx, a new lazy chunk, grew from 0 to ${TOLERANCE_BYTES + 1} gzip bytes.`,
		]);
		expect(growthViolations(head, base, [accept("new.tsx", TOLERANCE_BYTES + 1)])).toStrictEqual(
			[],
		);
		expect(growthViolations({ entry: 5000, lazy: { "new.tsx": 10 } }, base, [])).toStrictEqual([]);
	});

	it("breaks when a lazy chunk is gone and the entry grew, and passes a removed one", () => {
		const gone = { entry: 5000, lazy: { ...base.lazy, "old.tsx": 10 } };
		expect(
			growthViolations({ entry: 5001 + TOLERANCE_BYTES, lazy: base.lazy }, gone, []),
		).toStrictEqual([
			expect.stringContaining("The entry closure grew from"),
			"old.tsx is no longer a lazy chunk, and the entry grew: it may load with every page now.",
		]);
		expect(growthViolations(base, gone, [])).toStrictEqual([]);
	});
});

describe("ownAcceptances", () => {
	const spent: Acceptance = { load: "a.tsx", gzipBytes: 9000, acceptedBy: "#1" };
	const fresh: Acceptance = { load: "a.tsx", gzipBytes: 9500, acceptedBy: "#2" };

	it("drops an acceptance that the base already holds: its growth is in the base build", () => {
		expect(ownAcceptances([spent, fresh], [spent])).toStrictEqual([fresh]);
		expect(ownAcceptances([spent], [spent])).toStrictEqual([]);
	});

	it("keeps a spent acceptance from granting a later change growth past the base build", () => {
		// The load shrank after #1 merged; a later change that grows it back past the tolerance fails.
		const base: BundleSizes = { entry: 5000, lazy: { "a.tsx": 5000 } };
		const head: BundleSizes = { entry: 5000, lazy: { "a.tsx": 9000 } };
		expect(growthViolations(head, base, ownAcceptances([spent], [spent]))).toHaveLength(1);
	});
});

describe("acceptGrowth", () => {
	const base: BundleSizes = { entry: 5000, lazy: { "a.tsx": 5000, "b.tsx": 5000 } };

	it("accepts each load that this change grew past the tolerance, by who accepts it, and then checks clean", () => {
		const head: BundleSizes = {
			entry: 5000 + TOLERANCE_BYTES,
			lazy: { "a.tsx": 5001 + TOLERANCE_BYTES, "b.tsx": 5003, "new.tsx": 2 * TOLERANCE_BYTES },
		};
		const accepted = acceptGrowth(head, base, "#3", "a shared chunk");
		expect(accepted).toStrictEqual([
			{
				load: "a.tsx",
				gzipBytes: 5001 + TOLERANCE_BYTES,
				acceptedBy: "#3",
				reason: "a shared chunk",
			},
			{
				load: "new.tsx",
				gzipBytes: 2 * TOLERANCE_BYTES,
				acceptedBy: "#3",
				reason: "a shared chunk",
			},
		]);
		expect(growthViolations(head, base, accepted)).toStrictEqual([]);
	});

	it("accepts nothing when no load grew past the tolerance", () => {
		expect(acceptGrowth(base, base, "#3", "a shared chunk")).toStrictEqual([]);
	});
});
