import { describe, expect, it } from "vitest";

import { type BuiltChunk, budgetViolations, entryClosure, packageOf } from "./entry-closure";

const chunk = (overrides: Partial<BuiltChunk> & Pick<BuiltChunk, "fileName">): BuiltChunk => ({
	isEntry: false,
	imports: [],
	moduleIds: [],
	bytes: 100,
	...overrides,
});

const budget = { ceiling: 1000, slack: 200, routeOnlyPackages: ["recharts"] };

describe("packageOf", () => {
	it.each([
		["/repo/node_modules/.pnpm/recharts@3.8.0_x/node_modules/recharts/es6/index.js", "recharts"],
		[
			"/repo/node_modules/.pnpm/@tanstack+table-core@9.2.4/node_modules/@tanstack/table-core/build/index.js",
			"@tanstack/table-core",
		],
		[String.raw`C:\repo\node_modules\react-dom\index.js`, "react-dom"],
		["/repo/webapp/src/routes/__root.tsx", undefined],
		["\0rolldown/runtime.js", undefined],
	])("reads %s as %s", (moduleId, name) => {
		expect(packageOf(moduleId)).toBe(name);
	});
});

describe("entryClosure", () => {
	it("follows static imports transitively and stops at dynamic ones", () => {
		const closure = entryClosure([
			chunk({ fileName: "index.js", isEntry: true, imports: ["shell.js"] }),
			chunk({
				fileName: "shell.js",
				imports: ["index.js", "vendor.js"],
				moduleIds: ["/r/node_modules/react/index.js"],
			}),
			chunk({ fileName: "vendor.js", moduleIds: ["/r/src/a.ts"], bytes: 50 }),
			// Reached only through `import()`, which the closure does not follow.
			chunk({ fileName: "route.js", moduleIds: ["/r/node_modules/recharts/index.js"] }),
		]);
		expect(closure).toStrictEqual({ chunks: 3, bytes: 250, packages: ["react"] });
	});

	it("refuses a build without exactly one entry", () => {
		expect(() => entryClosure([chunk({ fileName: "a.js" })])).toThrow("one entry chunk");
	});
});

describe("budgetViolations", () => {
	it("passes a closure within the slack under the ceiling", () => {
		expect(budgetViolations({ chunks: 1, bytes: 900, packages: [] }, budget)).toStrictEqual([]);
	});

	it("reports a closure over the ceiling", () => {
		expect(budgetViolations({ chunks: 1, bytes: 1001, packages: [] }, budget)).toStrictEqual([
			expect.stringContaining("over its ceiling of 1000"),
		]);
	});

	it("asks to lower a ceiling that a saving left behind", () => {
		expect(budgetViolations({ chunks: 1, bytes: 799, packages: [] }, budget)).toStrictEqual([
			expect.stringContaining("Lower the ceiling to 799"),
		]);
	});

	it("reports a route-only package in the closure", () => {
		expect(
			budgetViolations({ chunks: 1, bytes: 900, packages: ["recharts"] }, budget),
		).toStrictEqual(["recharts is in the entry closure; only the routes that use it may load it."]);
	});
});
