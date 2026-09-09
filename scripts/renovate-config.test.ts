import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { isRecord, parseJson } from "./lib/json.ts";
import { BUNDLED_PINS } from "./lib/toolchain-pins.ts";

const parsedConfig: unknown = parseJson(await readFile("renovate.json", "utf8"));
assert.ok(isRecord(parsedConfig));
const config = parsedConfig;
assert.ok(Array.isArray(config.extends));
assert.ok(config.extends.every((entry) => typeof entry === "string"));
const extensions = config.extends;

void test("Gradle artifact updates use the repository JDK and retain human checksum review", async () => {
	assert.ok(isRecord(config.constraints));
	assert.equal(config.constraints.java, (await readFile(".java-version", "utf8")).trim());
	assert.ok(Array.isArray(config.enabledManagers));
	assert.ok(config.enabledManagers.includes("gradle"));
	assert.ok(config.enabledManagers.includes("gradle-wrapper"));
	assert.ok(!config.enabledManagers.includes("maven"));
	assert.ok(Array.isArray(config.packageRules));
	for (const manager of ["gradle", "gradle-wrapper"]) {
		const rule = config.packageRules
			.filter(isRecord)
			.find(
				(candidate) =>
					Array.isArray(candidate.matchManagers) &&
					candidate.matchManagers.includes(manager) &&
					Array.isArray(candidate.prBodyNotes),
			);
		assert.ok(rule, `${manager} updates must explain artifact verification`);
		assert.match(String(rule.prBodyNotes), /local-development#updating-java-dependencies/);
	}
});

void test("dependency updates normalize optional peer snapshots with the pinned pnpm", () => {
	assert.deepEqual(config.postUpdateOptions, ["pnpmDedupe"]);
});

/**
 * Datasources whose versions are release tags, which upstreams prefix with `v` while the pins here
 * are bare. Unless a manager strips the prefix, the version Renovate resolves for a pin is the tag
 * rather than the pin, so every rule that reads a version — `matchCurrentVersion` included — is
 * matched against a string the file does not contain.
 */
const RELEASE_TAG_DATASOURCES = new Set(["github-releases", "github-tags"]);

void test("Renovate creates bounded update PRs without a manual dispatch queue", () => {
	assert.ok(extensions.includes("config:best-practices"));
	assert.ok(!extensions.includes(":dependencyDashboardApproval"));
	assert.deepEqual(config.schedule, ["before 7am every weekday"]);
	assert.equal(config.prHourlyLimit, 2);
	assert.equal(config.prConcurrentLimit, 2);
	assert.equal(config.branchConcurrentLimit, 2);
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	for (const [updateType, currentVersion] of [
		["major", undefined],
		["minor", "/^0\\./"],
	]) {
		const rule = rules.find(
			(candidate) =>
				candidate.matchCurrentVersion === currentVersion &&
				Array.isArray(candidate.matchUpdateTypes) &&
				candidate.matchUpdateTypes.includes(updateType),
		);
		assert.ok(rule);
		assert.equal(rule.minimumReleaseAge, "7 days");
		assert.equal(rule.dependencyDashboardApproval, true);
		assert.equal(rule.groupName, null);
		assert.ok(Array.isArray(rule.addLabels) && rule.addLabels.includes("breaking"));
	}
});

void test("dependency pull requests explain the human-review requirement", () => {
	assert.ok(Array.isArray(config.prBodyNotes));
	assert.ok(
		config.prBodyNotes.some(
			(note) =>
				typeof note === "string" &&
				note.includes("Human review required") &&
				note.includes("https://docs.hephaestus.build/contributor/ci-cd#merge-policy"),
		),
	);
});

void test("compatibility overrides receive same-major fixes without restricting direct dependencies", () => {
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	for (const [selector, versions] of [
		["js-yaml@>=4.0.0", "4.x"],
		["markdown-it@<14.3.1", "14.x"],
		["uuid@<11.1.1", "11.x"],
	]) {
		const rule = rules.find(
			(candidate) =>
				Array.isArray(candidate.matchDepNames) && candidate.matchDepNames.includes(selector),
		);
		assert.ok(rule);
		// The npm extractor retains the whole override selector as depName, not packageName.
		assert.deepEqual(rule.matchDepNames, [selector]);
		assert.deepEqual(rule.matchManagers, ["npm"]);
		assert.deepEqual(rule.matchDepTypes, ["pnpm-workspace.overrides"]);
		assert.equal(rule.allowedVersions, versions);
		assert.equal(rule.enabled, undefined);
		assert.equal(rule.matchUpdateTypes, undefined);
	}
});

void test("every pin of one toolchain version moves in a single pull request", () => {
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	const lastUngroupedRule = rules.findLastIndex((rule) => rule.groupName === null);
	assert.ok(
		lastUngroupedRule >= 0,
		"high-risk updates must be ungrouped before toolchain groups reinstate them",
	);
	for (const [depName, groupName] of [
		["node", "Node.js toolchain"],
		["pnpm", "pnpm toolchain"],
		["ghcr.io/pnpm/pnpm", "pnpm toolchain"],
		["@earendil-works/pi-coding-agent", "Pi SDK"],
	]) {
		const index = rules.findLastIndex(
			(rule) => Array.isArray(rule.matchDepNames) && rule.matchDepNames.includes(depName),
		);
		assert.equal(rules[index]?.groupName, groupName, `${depName} must be grouped as ${groupName}`);
		assert.equal(rules[index]?.matchUpdateTypes, undefined, `${depName} groups every update type`);
		assert.ok(index > lastUngroupedRule, `${depName} must follow the high-risk update rules`);
	}
});

void test("Vercel AI SDK packages move together", async () => {
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	const ruleIndex = rules.findLastIndex((rule) => rule.groupName === "Vercel AI SDK");
	assert.ok(ruleIndex > rules.findLastIndex((rule) => rule.groupName === null));
	const rule = rules[ruleIndex];
	assert.ok(rule);
	assert.deepEqual(rule.matchManagers, ["npm"]);
	assert.deepEqual(rule.matchFileNames, ["webapp/package.json"]);
	assert.deepEqual(rule.matchPackageNames, ["ai", "@ai-sdk/react"]);
	assert.equal(rule.matchUpdateTypes, undefined);
	// Renovate creates no branch at all below `minimumGroupSize`, so a floor above the number of
	// pins the rule can match would hold every update in the group back, security ones included.
	const manifest: unknown = parseJson(await readFile("webapp/package.json", "utf8"));
	assert.ok(isRecord(manifest));
	const dependencies: unknown = manifest.dependencies;
	assert.ok(isRecord(dependencies));
	assert.ok(Array.isArray(rule.matchPackageNames));
	const pinned = rule.matchPackageNames.filter(
		(name) => typeof name === "string" && name in dependencies,
	);
	assert.equal(rule.minimumGroupSize, pinned.length);
});

void test("routine update groups preserve repository boundaries", () => {
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	const groups = [
		["repository tooling dependencies", "npm", "package.json", undefined],
		["webapp development dependencies", "npm", "webapp/package.json", ["devDependencies"]],
		["server dependencies", "gradle", undefined, undefined],
	] as const;
	for (const [groupName, manager, fileName, depTypes] of groups) {
		const rule = rules.find((candidate) => candidate.groupName === groupName);
		assert.ok(rule);
		assert.deepEqual(rule.matchManagers, [manager]);
		assert.deepEqual(rule.matchFileNames, fileName === undefined ? undefined : [fileName]);
		assert.deepEqual(rule.matchDepTypes, depTypes);
		assert.ok(Array.isArray(rule.matchUpdateTypes));
		assert.deepEqual(
			new Set(rule.matchUpdateTypes),
			new Set(["minor", "patch", "digest", "pin", "pinDigest"]),
		);
	}
});

void test("vulnerability remediation bypasses normal update latency", () => {
	assert.equal(config.osvVulnerabilityAlerts, true);
	assert.equal(config.dependencyDashboardOSVVulnerabilitySummary, "unresolved");
	assert.deepEqual(config.vulnerabilityAlerts, {
		enabled: true,
		minimumReleaseAge: null,
		dependencyDashboardApproval: false,
		automerge: false,
		groupName: null,
		labels: ["security", "dependencies"],
	});
});

void test("every custom manager reads every file it claims to read", async () => {
	const sources = new Map<string, string[]>([
		["Track Dockerfile ARG version pins", ["docker/agents/pi/Dockerfile", "webapp/Dockerfile"]],
		[
			"Track release security tool versions",
			[".github/actions/setup-release-security-tools/action.yml"],
		],
		["Track the Zizmor CLI version", [".github/workflows/cicd.yml"]],
		["Track the pack CLI version", [".github/workflows/reusable-docker-build.yml"]],
		["Track the buildpacks run image", [".github/workflows/cicd.yml"]],
		[
			"Track the builder and buildpack images in the project descriptor",
			["server/application/project.toml"],
		],
		["Track the OpenAPI Generator CLI distribution", ["openapitools.json"]],
		["Track the Node.js pin in devEngines", ["package.json"]],
		["Track the pnpm pin in devEngines", ["package.json"]],
		[
			"Track the Pi SDK pin in the live agent tests",
			[
				"server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/mentor/live/MentorLiveLlmTest.java",
				"server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/mentor/live/MentorSandboxStressTest.java",
				"server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/practice/live/PracticeRunnerLiveLlmTest.java",
			],
		],
		["Track release image tags and digests", ["security/release-images.json"]],
		["Track the isolated Semgrep scanner image", [".github/workflows/semgrep.yml"]],
	]);
	assert.ok(Array.isArray(config.customManagers));
	assert.ok(config.customManagers.every(isRecord));
	assert.deepEqual(
		new Set(config.customManagers.map((manager) => manager.description)),
		new Set(sources.keys()),
	);
	for (const manager of config.customManagers) {
		if (typeof manager.description !== "string") throw new TypeError("manager description");
		const description = manager.description;
		assert.ok(Array.isArray(manager.matchStrings), `${description} declares no matchStrings`);
		assert.ok(
			manager.matchStrings.every((pattern) => typeof pattern === "string"),
			`${description} declares a matchStrings entry that is not a string`,
		);
		assert.ok(
			Array.isArray(manager.managerFilePatterns),
			`${description} declares no managerFilePatterns`,
		);
		assert.ok(
			manager.managerFilePatterns.every((pattern) => typeof pattern === "string"),
			`${description} declares a managerFilePatterns entry that is not a string`,
		);
		const files = sources.get(description);
		assert.ok(files);
		const datasources = new Set(
			typeof manager.datasourceTemplate === "string" ? [manager.datasourceTemplate] : [],
		);
		for (const file of files) {
			assert.ok(
				// `managerFilePatterns` accepts a glob as well as a `/`-delimited regex; only the
				// latter survives the slice, so anything else is not a pattern this check can read.
				manager.managerFilePatterns.some(
					(pattern) =>
						pattern.startsWith("/") &&
						pattern.endsWith("/") &&
						new RegExp(pattern.slice(1, -1)).test(file),
				),
				`${description} does not select ${file}`,
			);
			const content = await readFile(file, "utf8");
			assert.ok(
				manager.matchStrings.some((pattern) => new RegExp(pattern, "m").test(content)),
				`${description} no longer extracts a dependency from ${file}`,
			);
			// Only dependencies this manager extracts matter; a file may have several managers.
			for (const pattern of manager.matchStrings)
				for (const match of content.matchAll(new RegExp(pattern, "gm"))) {
					const datasource = match.groups?.datasource;
					if (datasource) datasources.add(datasource);
				}
		}
		if ([...datasources].some((datasource) => RELEASE_TAG_DATASOURCES.has(datasource)))
			assert.ok(
				manager.extractVersionTemplate === "^v(?<version>.*)$" ||
					manager.matchStrings.some((pattern) => pattern.includes("(?<extractVersion>")),
				`${description} reads release tags without stripping their v prefix`,
			);
	}
});

void test("the tools vite-plus bundles move only with vite-plus", () => {
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	const frozen = rules.find(
		(rule) => Array.isArray(rule.matchDepNames) && rule.matchDepNames.includes("oxlint"),
	);
	assert.ok(frozen, "a rule must disable the bundled tools");
	assert.ok(Array.isArray(frozen.matchDepNames));
	// The set Renovate leaves alone is the set gate:toolchain holds to the bundle, no more, no less.
	assert.deepEqual(new Set(frozen.matchDepNames), new Set(Object.keys(BUNDLED_PINS)));
	const bump = rules.find(
		(rule) => Array.isArray(rule.matchDepNames) && rule.matchDepNames.includes("vite-plus"),
	);
	assert.ok(bump, "the vite-plus bump must say what to run");
	assert.match(String(bump.prBodyNotes), /sync:toolchain-pins/);
});
