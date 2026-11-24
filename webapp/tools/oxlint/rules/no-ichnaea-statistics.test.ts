import { ruleTester } from "../rule-tester.ts";
import { noIchnaeaStatistics } from "./no-ichnaea-statistics.ts";

const filename = "scripts/feedback-eval/report.ts";

ruleTester.run("no-ichnaea-statistics", noIchnaeaStatistics, {
	valid: [
		// The CLI, the contracts and the schemas are how Hephaestus drives Ichnaea and reads its records.
		{ code: 'import { main } from "@tumaet/ichnaea";', filename },
		{ code: 'import { Decision } from "@tumaet/ichnaea/contracts";', filename },
		{ code: 'import { digest } from "@hephaestus-build/ichnaea/contracts";', filename },
		{ code: 'const schema = await import("@tumaet/ichnaea/schemas/decision.json");', filename },
		// A local directory that is called `ichnaea` is not the package.
		{ code: 'import { cases } from "./ichnaea/stats/cases.ts";', filename },
		{ code: 'import { stats } from "../ichnaea/calibration.ts";', filename },
		// A package whose name only contains the word.
		{ code: 'import { mean } from "ichnaea-stats-fork/stats";', filename },
		// A specifier computed at runtime is not one the rule can read.
		{ code: 'const loaded = await import("@tumaet/ichnaea/" + part);', filename },
		// The name as data, passed to a function that loads nothing.
		{ code: 'assert.equal(specifier, "@tumaet/ichnaea/stats");', filename },
		{ code: 'const text = JSON.stringify("@hephaestus-build/ichnaea/calibration");', filename },
		// A local function called `require` is not Node's loader.
		{
			code: 'function require(name: string) { return name; }\nrequire("@tumaet/ichnaea/stats");',
			filename,
		},
		// A destructuring pattern selects part of its initializer, not the whole of it.
		{
			code: 'const { 999: entry = "node:path" } = "@tumaet/ichnaea/stats";\nawait import(entry);',
			filename,
		},
		{
			code: 'import { createRequire } from "node:module";\nconst { inspect = JSON.stringify } = createRequire(import.meta.url);\ninspect("@tumaet/ichnaea/stats");',
			filename,
		},
		{
			// oxlint-disable-next-line no-template-curly-in-string -- The fixture is source text that holds a template literal.
			code: "const loaded = await import(`@tumaet/ichnaea/${part}`);",
			filename,
		},
		// A test double called `createRequire` is not Node's: it comes from no `node:module`.
		{
			code: 'const fixtureModule = { createRequire: () => (specifier: string) => ({ specifier }) };\nconst load = fixtureModule.createRequire();\nload("@tumaet/ichnaea/stats");',
			filename,
		},
		{
			code: 'function createRequire() { return (specifier: string) => specifier; }\nconst load = createRequire();\nload("@tumaet/ichnaea/stats");',
			filename,
		},
		{
			code: 'const fixtureProcess = { getBuiltinModule: (name: string) => ({ name, createRequire: () => JSON.stringify }) };\nconst record = fixtureProcess.getBuiltinModule("node:module").createRequire();\nrecord("@tumaet/ichnaea/stats");',
			filename,
		},
		// A variable that can change is not a specifier the rule can read.
		{ code: 'let entry = "@tumaet/ichnaea/stats"; entry = "x"; await import(entry);', filename },
	],
	invalid: [
		{
			code: 'import { stratifiedExactTest } from "@tumaet/ichnaea/stats";',
			filename,
			errors: [{ messageId: "statistics", data: { specifier: "@tumaet/ichnaea/stats" } }],
		},
		{
			code: 'import { calibrate } from "@tumaet/ichnaea/calibration";',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			// The package before its rename to `@tumaet`.
			code: 'import { wilson } from "@hephaestus-build/ichnaea/stats";',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			// A type is a dependency on the library as much as a value is.
			code: 'import type { Interval } from "@tumaet/ichnaea/stats";',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'export { stratifiedExactTest } from "@tumaet/ichnaea/stats";',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'export * from "@tumaet/ichnaea/calibration";',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const stats = await import("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: "const stats = await import(`@tumaet/ichnaea/stats`);",
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'import { createRequire } from "node:module";\nconst load = createRequire(import.meta.url);\nconst stats = load("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'import nodeModule from "node:module";\nconst stats = nodeModule.createRequire(import.meta.url)("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const stats = require("@tumaet/ichnaea/calibration");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const where = require.resolve("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			// The specifier in a constant, read through a type assertion.
			code: 'const entry = "@hephaestus-build/ichnaea/stats" as const;\nconst stats: unknown = await import(entry);',
			filename,
			errors: [{ messageId: "statistics", data: { specifier: "@hephaestus-build/ichnaea/stats" } }],
		},
		{
			code: 'import { createRequire } from "node:module";\nconst entry = "@tumaet/ichnaea/calibration";\nconst stats: unknown = createRequire(import.meta.url)(entry);',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			// The package name in a constant, completed in a template.
			// oxlint-disable-next-line no-template-curly-in-string -- The fixture is source text that holds a template literal.
			code: 'const ichnaea = "@hephaestus-build/ichnaea";\nconst stats: unknown = await import(`${ichnaea}/stats`);',
			filename,
			errors: [{ messageId: "statistics", data: { specifier: "@hephaestus-build/ichnaea/stats" } }],
		},
		{
			code: 'const ichnaea = "@tumaet/ichnaea";\nconst stats: unknown = await import(ichnaea + "/calibration");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const stats: unknown = process.getBuiltinModule("node:module").createRequire(import.meta.url)("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const { createRequire } = await import("node:module");\nconst load = createRequire(import.meta.url);\nconst stats: unknown = load("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'import { createRequire as createNodeRequire } from "node:module";\nconst load = createNodeRequire(import.meta.url);\nconst stats: unknown = load("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const { createRequire: createNodeRequire } = await import("node:module");\nconst stats: unknown = createNodeRequire(import.meta.url)("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'import * as nodeModule from "node:module";\nconst stats: unknown = nodeModule.createRequire(import.meta.url)("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'import { getBuiltinModule } from "node:process";\nconst stats: unknown = getBuiltinModule("node:module").createRequire(import.meta.url)("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'import { Module } from "node:module";\nconst load = Module.createRequire(import.meta.url);\nconst stats: unknown = load("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const nodeModule = process.getBuiltinModule("node:module");\nconst stats: unknown = nodeModule.createRequire(import.meta.url)("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const host = process;\nconst stats: unknown = host.getBuiltinModule("module").createRequire(import.meta.url)("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			// A constant that holds a loader is the loader.
			code: 'import { createRequire } from "node:module";\nconst load = createRequire(import.meta.url);\nconst again = load;\nconst stats: unknown = again("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'type Wilson = typeof import("@tumaet/ichnaea/stats").wilson;',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'import stats = require("@tumaet/ichnaea/stats");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'const where = import.meta.resolve("@tumaet/ichnaea/calibration");',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			// The built files, reached past the package's exports by a path.
			code: 'import { wilson } from "../../node_modules/@tumaet/ichnaea/dist/stats/index.js";',
			filename,
			errors: [{ messageId: "statistics" }],
		},
		{
			code: 'import { holm } from "@tumaet/ichnaea/stats/holm";',
			filename,
			errors: [{ messageId: "statistics" }],
		},
	],
});
