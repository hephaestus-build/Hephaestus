/**
 * Fails when a change takes from an operator or a script author what the latest stable release
 * promised, unless it heads for a major release. Below 1.0.0 it reads nothing and refuses nothing,
 * so the 1.0 cutovers land freely. docs/contributor/ci-cd.mdx § Public contract gate owns the rules
 * and docs/admin/compatibility-policy.mdx owns the promise.
 *
 * Usage: vp run gate:public-contract [--base vX.Y.Z[-rc.N]]
 */
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { parseArgs } from "node:util";

import { parse } from "yaml";

import {
	fileAt,
	headsForMajorRelease,
	latestStableTag,
	majorOf,
} from "./check-api-compatibility.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { narrowedChartValues, removedChartValues } from "./verify-changesets.ts";

const ROOT = path.resolve(import.meta.dirname, "..");

const CHART_SCHEMA = "deploy/chart/hephaestus/values.schema.json";
const ALERT_RULES = "deploy/chart/hephaestus/files/hephaestus-alerts.yml";
const CATALOG_SCHEMA =
	"server/application/src/main/resources/practices/default-catalog.schema.json";
const CATALOG = "server/application/src/main/resources/practices/default-catalog.json";
const SCRIPT_CONTRACT = "docker/agents/practice-scripts/lib/core/contract.ts";
const ROUTE_TREE = "webapp/src/routeTree.gen.ts";

/**
 * The files that the `exports` map of `docker/agents/practice-scripts/package.json` names: what a
 * practice script can import. A script cannot import any other module.
 */
const SCRIPT_ENTRY_POINTS = [
	"docker/agents/practice-scripts/lib/api.ts",
	"docker/agents/practice-scripts/kits/scm/api.ts",
	"docker/agents/practice-scripts/kits/chat/index.ts",
	"docker/agents/practice-scripts/kits/docs/index.ts",
];

/** The script contract's values that a change may not widen or narrow in a minor release. */
const CLOSED_CONSTANTS = ["CONTRACT_VERSION", "OUTCOMES", "SEVERITIES"];

/** Routes that the links Hephaestus posted in provider comments, Slack and email point to. */
export const DEEP_LINKS = [
	"/w/$workspaceSlug/feedback/$itemId",
	"/w/$workspaceSlug/conversation",
	"/settings",
	"/unsubscribe",
];

const CONTRACT_FILES = [
	CHART_SCHEMA,
	ALERT_RULES,
	CATALOG_SCHEMA,
	CATALOG,
	SCRIPT_CONTRACT,
	...SCRIPT_ENTRY_POINTS,
];

/** Identifiers in PromQL that are not metrics. */
const PROMQL_WORDS = new Set([
	"and",
	"or",
	"unless",
	"bool",
	"offset",
	"inf",
	"nan",
	"group_left",
	"group_right",
]);

/** The metrics that a PromQL expression reads: its names, without comments, labels, strings and functions. */
export function metricsIn(expression: string): string[] {
	const bare = expression
		.replaceAll(/#.*$/gmu, "")
		.replaceAll(/"[^"]*"/gu, "")
		.replaceAll(/\{[^}]*\}/gu, "")
		.replaceAll(/\[[^\]]*\]/gu, "")
		.replaceAll(/\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)/gu, "");
	return [...bare.matchAll(/(?<![\w:.])[a-zA-Z_:][\w:]*(?![\w:]*\s*\()/gu)]
		.map(([name]) => name)
		.filter((name) => !PROMQL_WORDS.has(name.toLowerCase()));
}

/** Each alert name of a rules file as `alert X`, and each metric its rules read as `metric Y`. */
export function alertNames(rules: string): Set<string> {
	const names = new Set<string>();
	for (const group of asArray(asRecord(parse(rules), ALERT_RULES).groups, "groups")) {
		for (const rule of asArray(asRecord(group, "group").rules, "rules")) {
			const { alert, expr } = asRecord(rule, "rule");
			if (typeof alert === "string") {
				names.add(`alert ${alert}`);
			}
			for (const metric of metricsIn(asString(expr, "rule expr"))) {
				names.add(`metric ${metric}`);
			}
		}
	}
	return names;
}

/** Each group and practice slug of the bundled catalog. */
export function catalogSlugs(catalog: string): Set<string> {
	const slugs = new Set<string>();
	for (const group of asArray(asRecord(parseJson(catalog), CATALOG).groups, "groups")) {
		const { slug, practices } = asRecord(group, "group");
		slugs.add(`group ${asString(slug, "group slug")}`);
		for (const practice of asArray(practices, "practices")) {
			slugs.add(`practice ${asString(asRecord(practice, "practice").slug, "practice slug")}`);
		}
	}
	return slugs;
}

/** The names of one `export { … }` list, each under the name that it exports. */
const namesInList = (list: string) =>
	list
		.replaceAll(/\/\*[\s\S]*?\*\/|\/\/.*$/gmu, "")
		.split(",")
		.map(
			(entry) =>
				entry
					.trim()
					.split(/\s+as\s+/u)
					.at(-1)
					?.replace(/^type\s+/u, "") ?? "",
		)
		.filter((name) => name !== "");

/** What a TypeScript module exports: its declarations, its lists, its default and each `export *`. */
export function exportedNames(source: string): Set<string> {
	const declared = source.matchAll(
		/^export\s+(?:declare\s+)?(?:abstract\s+)?(?:async\s+)?(?:function\*?|const\s+enum|const|let|class|interface|type|enum|namespace)\s+(?<name>[A-Za-z_$][\w$]*)/gmu,
	);
	const listed = source.matchAll(/^export\s+(?:type\s+)?\{(?<list>[^}]*)\}/gmu);
	const whole = source.matchAll(/^export\s+\*(?:\s+as\s+[\w$]+)?\s+from\s+["'][^"']+["']/gmu);
	return new Set([
		...[...declared].flatMap(({ groups }) => groups?.name ?? []),
		...[...listed].flatMap(({ groups }) => namesInList(groups?.list ?? "")),
		...[...whole].map(([statement]) => statement.replaceAll(/\s+/gu, " ")),
		...(/^export\s+default\b/mu.test(source) ? ["default"] : []),
	]);
}

/** The text of `export const name = … as const`, without its spacing. Undefined when it is not there. */
export function closedConstant(source: string, name: string): string | undefined {
	return new RegExp(`^export const ${name} = (?<value>[\\s\\S]*?) as const;`, "mu")
		.exec(source)
		?.groups?.value?.replaceAll(/\s+/gu, "");
}

const contentOf = (files: ReadonlyMap<string, string>, file: string) => {
	const text = files.get(file);
	if (text === undefined) {
		throw new Error(
			`${file} is missing. Update the file list in scripts/check-public-contract.ts.`,
		);
	}
	return text;
};

/** Each contract file, as `reader` gives it. */
const contractFiles = async (reader: (file: string) => Promise<string>) =>
	new Map(
		await Promise.all(CONTRACT_FILES.map(async (file) => [file, await reader(file)] as const)),
	);

const lost = (before: ReadonlySet<string>, after: ReadonlySet<string>) =>
	[...before].filter((name) => !after.has(name));

/** Each thing that `base` promised and `head` takes away. Both map a repository path to its text. */
export function findings(
	base: ReadonlyMap<string, string>,
	head: ReadonlyMap<string, string>,
	routeTree: string,
): string[] {
	const baseOf = (file: string) => contentOf(base, file);
	const headOf = (file: string) => contentOf(head, file);
	const chartBase = parseJson(baseOf(CHART_SCHEMA));
	const chartHead = parseJson(headOf(CHART_SCHEMA));
	return [
		...removedChartValues(chartBase, chartHead).map((name) => `Chart value ${name} is gone`),
		...narrowedChartValues(chartBase, chartHead).map((line) => `Chart value ${line}`),
		...lost(alertNames(baseOf(ALERT_RULES)), alertNames(headOf(ALERT_RULES))).map(
			(name) => `The shipped alert rules lost ${name}`,
		),
		...removedChartValues(parseJson(baseOf(CATALOG_SCHEMA)), parseJson(headOf(CATALOG_SCHEMA))).map(
			(name) => `The practice definition schema lost ${name}`,
		),
		...lost(catalogSlugs(baseOf(CATALOG)), catalogSlugs(headOf(CATALOG))).map(
			(name) => `The catalog lost ${name}`,
		),
		...SCRIPT_ENTRY_POINTS.flatMap((file) =>
			lost(exportedNames(baseOf(file)), exportedNames(headOf(file))).map(
				(name) => `${file} no longer exports ${name}`,
			),
		),
		...CLOSED_CONSTANTS.flatMap((name) => {
			const before = closedConstant(baseOf(SCRIPT_CONTRACT), name);
			if (before === undefined) {
				throw new Error(`${SCRIPT_CONTRACT} of the base has no ${name}.`);
			}
			const after = closedConstant(headOf(SCRIPT_CONTRACT), name);
			return before === after
				? []
				: [`The script contract's ${name} changed from ${before} to ${String(after)}`];
		}),
		...DEEP_LINKS.filter((route) => !routeTree.includes(`'${route}'`)).map(
			(route) => `Posted links lead to ${route}, which no route serves`,
		),
	];
}

async function main(): Promise<void> {
	const { values } = parseArgs({ options: { base: { type: "string" } } });
	if (values.base !== undefined && !/^v\d+\.\d+\.\d+(?:-rc\.\d+)?$/u.test(values.base)) {
		throw new Error("Usage: vp run gate:public-contract [--base vX.Y.Z[-rc.N]]");
	}
	const base =
		values.base ??
		latestStableTag(
			await output("git", ["ls-remote", "--tags", "--refs", "origin", "v*"], { cwd: ROOT }),
		);
	if (majorOf(base) < 1) {
		console.log(`The public contract starts at 1.0.0. The latest release is ${base}.`);
		// A warning only: a cutover can move these files, and it updates the list before 1.0.0.
		const missing = [...CONTRACT_FILES, ROUTE_TREE].filter(
			(file) => !existsSync(path.join(ROOT, file)),
		);
		for (const file of missing) {
			console.log(
				`::warning title=Public contract::${file} is gone. Update the file list in scripts/check-public-contract.ts before 1.0.0.`,
			);
		}
		return;
	}
	if (await headsForMajorRelease(ROOT, base)) {
		console.log(`The change heads for a major release after ${base}, so it can break it.`);
		return;
	}
	const problems = findings(
		await contractFiles(async (file) => fileAt(base, file)),
		await contractFiles(async (file) => readFile(path.join(ROOT, file), "utf8")),
		await readFile(path.join(ROOT, ROUTE_TREE), "utf8"),
	);
	for (const problem of problems) {
		console.log(`::error title=Public contract::${problem}.`);
	}
	if (problems.length > 0) {
		console.log(
			`::error title=Public contract::This change breaks what ${base} promised. Keep it, or add a major changeset. See docs/admin/compatibility-policy.mdx.`,
		);
		process.exitCode = 1;
		return;
	}
	console.log(`Nothing that ${base} promised is gone.`);
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	}
}
