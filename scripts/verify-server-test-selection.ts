import assert from "node:assert/strict";
import { glob, readFile, rm } from "node:fs/promises";
import { resolve } from "node:path";

import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

import { isRecord } from "./lib/json.ts";
import { run } from "./lib/process.ts";

export function testIdentities(xml: string): Set<string> {
	assert.equal(SyntaxValidator.validate(xml), true, "Invalid JUnit XML");
	const parsed: unknown = new XMLParser({ ignoreAttributes: false }).parse(xml);
	assert.ok(isRecord(parsed) && isRecord(parsed.testsuite), "Expected a JUnit testsuite");
	const cases: unknown = parsed.testsuite.testcase;
	if (cases === undefined) return new Set();
	const tests: unknown[] = Array.isArray(cases) ? cases : [cases];
	return new Set(
		tests.map((entry) => {
			assert.ok(isRecord(entry), "Invalid JUnit testcase");
			const className = entry["@_classname"];
			const name = entry["@_name"];
			assert.ok(typeof className === "string");
			assert.ok(typeof name === "string");
			return `${className}#${name}`;
		}),
	);
}

export function assertCoverage(
	expected: Set<string>,
	groups: Set<string>[],
	disjoint: boolean,
): void {
	assert.ok(expected.size > 0, "Discovery returned no tests");
	assert.ok(
		groups.every((group) => group.size > 0),
		"A required tier or shard contains no tests",
	);
	const actual = new Set(groups.flatMap((group) => [...group]));
	assert.deepEqual(
		[...expected.difference(actual)].toSorted(),
		[],
		"Tests omitted by every tier or shard",
	);
	assert.deepEqual(
		[...actual.difference(expected)].toSorted(),
		[],
		"Selected tests outside the inventory",
	);
	if (disjoint) {
		assert.equal(
			groups.reduce((count, group) => count + group.size, 0),
			actual.size,
			"Shards overlap",
		);
	}
}

async function readIdentities(directory: string): Promise<Set<string>> {
	const identities = new Set<string>();
	for await (const file of glob(`${directory}/TEST-*.xml`)) {
		for (const identity of testIdentities(await readFile(file, "utf8"))) identities.add(identity);
	}
	return identities;
}

async function main(): Promise<void> {
	const root = resolve(import.meta.dirname, "..");
	const server = resolve(root, "server");
	const reports = resolve(server, "application/build/test-selection");
	const wrapper = resolve(import.meta.dirname, "run-gradlew.ts");
	const tiers = ["test", "architectureTest", "integrationTest", "databaseTest"];
	// Dry-run reports are isolated from execution and coverage reports.
	await rm(reports, { recursive: true, force: true });
	await run(
		process.execPath,
		[
			wrapper,
			":application:testInventory",
			...tiers.map((tier) => `:application:${tier}`),
			"-PtestSelection=true",
		],
		{
			cwd: server,
			env: { ...process.env, HEPHAESTUS_INTEGRATION_SHARD: "" },
		},
	);
	const all = await readIdentities(resolve(reports, "testInventory", "xml"));
	const tierTests = await Promise.all(
		tiers.map((tier) => readIdentities(resolve(reports, tier, "xml"))),
	);
	assertCoverage(all, tierTests, false);
	const integration = await readIdentities(resolve(reports, "integrationTest", "xml"));
	const shards: Set<string>[] = [];
	for (const shard of ["providers-and-startup", "application"]) {
		await rm(resolve(reports, "integrationTest"), { recursive: true, force: true });
		await run(process.execPath, [wrapper, ":application:integrationTest", "-PtestSelection=true"], {
			cwd: server,
			env: { ...process.env, HEPHAESTUS_INTEGRATION_SHARD: shard },
		});
		shards.push(await readIdentities(resolve(reports, "integrationTest", "xml")));
	}
	assertCoverage(integration, shards, true);
	console.log(
		`JUnit discovery: ${all.size} non-live discovery entries covered; ${integration.size} integration entries partitioned exactly once.`,
	);
}

if (import.meta.main) await main();
