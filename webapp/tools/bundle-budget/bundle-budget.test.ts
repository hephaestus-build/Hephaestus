import { describe, expect, it } from "vitest";

import {
	type BuiltChunk,
	type BundleSizes,
	checkBudget,
	lazyKey,
	measure,
	TOLERANCE_BYTES,
	updatedBaseline,
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

describe("checkBudget", () => {
	const measured = measure(build);
	const baseline: BundleSizes = { entry: 400, lazy: { [PROFILE]: 450 } };
	const kept = { violations: [], lowerable: [] };

	it("keeps a build that grew within the tolerance", () => {
		expect(checkBudget(measured, baseline, [])).toStrictEqual(kept);
		expect(
			checkBudget(measured, { entry: 400 - TOLERANCE_BYTES, lazy: { [PROFILE]: 450 } }, []),
		).toStrictEqual(kept);
	});

	it("breaks on growth past the tolerance", () => {
		expect(
			checkBudget(measured, { entry: 400 - TOLERANCE_BYTES - 1, lazy: baseline.lazy }, []),
		).toStrictEqual({
			violations: [expect.stringContaining("The entry closure grew from")],
			lowerable: [],
		});
	});

	it("passes a saving and says the baseline can come down", () => {
		expect(
			checkBudget(measured, { entry: 400, lazy: { [PROFILE]: 450 + TOLERANCE_BYTES + 1 } }, []),
		).toStrictEqual({
			violations: [],
			lowerable: [`${PROFILE} shrank from ${451 + TOLERANCE_BYTES} to 450 gzip bytes.`],
		});
	});

	it("counts a new lazy chunk as growth from nothing", () => {
		const large = measure([
			...build.slice(0, 2),
			chunk({
				fileName: "profile.js",
				isDynamicEntry: true,
				facadeModule: PROFILE,
				gzipBytes: TOLERANCE_BYTES + 1,
			}),
		]);
		expect(checkBudget(large, { entry: 400, lazy: {} }, [])).toStrictEqual({
			violations: [
				`${PROFILE}, a new lazy chunk, grew from 0 to ${TOLERANCE_BYTES + 1} gzip bytes.`,
			],
			lowerable: [],
		});
		expect(checkBudget(measured, { entry: 400, lazy: {} }, [])).toStrictEqual(kept);
	});

	it("breaks when a lazy chunk is gone and the entry grew, and passes a removed one", () => {
		const gone = { ...baseline.lazy, "src/routes/old.tsx": 10 };
		expect(
			checkBudget(measured, { entry: 400 - TOLERANCE_BYTES - 1, lazy: gone }, []).violations,
		).toStrictEqual([
			expect.stringContaining("The entry closure grew from"),
			"src/routes/old.tsx is no longer a lazy chunk, and the entry grew: it may load with every page now.",
		]);
		expect(checkBudget(measured, { entry: 400, lazy: gone }, [])).toStrictEqual({
			violations: [],
			lowerable: ["src/routes/old.tsx is no longer a lazy chunk."],
		});
	});

	it("breaks on a route-only package in the entry closure", () => {
		expect(checkBudget(measured, baseline, ["react"]).violations).toStrictEqual([
			"react is in the entry closure; only the routes that use it may load it.",
		]);
	});
});

describe("updatedBaseline", () => {
	const baseline: BundleSizes = {
		entry: 5000,
		lazy: { "a.tsx": 5000, "b.tsx": 5000, "gone.tsx": 5 },
	};
	const checksClean = (measured: BundleSizes) => {
		const updated = updatedBaseline(measured, baseline);
		expect(checkBudget({ sizes: measured, entryPackages: [] }, updated, [])).toStrictEqual({
			violations: [],
			lowerable: [],
		});
		return updated;
	};

	it("keeps a size that moved within the tolerance, in both directions", () => {
		const updated = checksClean({
			entry: 5000 + TOLERANCE_BYTES,
			lazy: { "a.tsx": 5000 - TOLERANCE_BYTES, "b.tsx": 5003 },
		});
		expect(updated).toStrictEqual({ entry: 5000, lazy: { "a.tsx": 5000, "b.tsx": 5000 } });
	});

	it("records growth and savings past the tolerance, a new chunk, and no removed chunk", () => {
		const measured: BundleSizes = {
			entry: 5001 + TOLERANCE_BYTES,
			lazy: { "a.tsx": 4999 - TOLERANCE_BYTES, "b.tsx": 5000, "new.tsx": 7 },
		};
		expect(checksClean(measured)).toStrictEqual(measured);
	});

	it("keeps every size of a build within the tolerance", () => {
		const updated = checksClean({
			entry: 5001,
			lazy: { "a.tsx": 4999, "b.tsx": 5000, "gone.tsx": 5 },
		});
		expect(updated).toStrictEqual(baseline);
	});

	it("records the build when no baseline can be read", () => {
		const measured: BundleSizes = { entry: 7, lazy: { "a.tsx": 8 } };
		expect(updatedBaseline(measured, undefined)).toStrictEqual(measured);
	});
});
