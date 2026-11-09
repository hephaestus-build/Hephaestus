import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { parse } from "yaml";

import { isSet } from "./lib/env.ts";
import { isRecord, parseJson } from "./lib/json.ts";
import { BUNDLED_PINS } from "./lib/toolchain-pins.ts";

const parsedConfig: unknown = parseJson(await readFile(".github/renovate.json", "utf8"));
assert.ok(isRecord(parsedConfig));
const config = parsedConfig;
assert.ok(Array.isArray(config.extends));
assert.ok(config.extends.every((entry) => typeof entry === "string"));
const extensions = config.extends;

void test("Gradle artifact updates use the repository JDK and retain human checksum review", async () => {
	assert.ok(isRecord(config.constraints));
	const javaVersion = await readFile(".java-version", "utf8");
	assert.equal(config.constraints.java, javaVersion.trim());
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
		assert.match(String(rule.prBodyNotes), /local-development#updating-java-dependencies/u);
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
	// One weekly batch per group keeps at most two dependency pull requests in the merge queue.
	assert.deepEqual(config.schedule, ["before 7am on monday"]);
	assert.ok(isRecord(config.lockFileMaintenance));
	assert.deepEqual(config.lockFileMaintenance.schedule, [
		"before 7am on the first day of the month",
	]);
	assert.equal(config.prHourlyLimit, 2);
	assert.equal(config.prConcurrentLimit, 2);
	assert.equal(config.branchConcurrentLimit, 2);
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	for (const [updateType, currentVersion] of [
		["major", undefined],
		["minor", String.raw`/^0\./`],
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

void test("compatibility overrides receive same-major fixes without restricting direct dependencies", async () => {
	const workspace: unknown = parse(await readFile("pnpm-workspace.yaml", "utf8"));
	assert.ok(isRecord(workspace) && isRecord(workspace.overrides));
	const { overrides } = workspace;
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	for (const name of ["js-yaml", "uuid"]) {
		const selectors = Object.keys(overrides).filter(
			(selector) => selector === name || selector.startsWith(`${name}@`),
		);
		assert.equal(selectors.length, 1, `${name} must have one compatibility override`);
		const [selector] = selectors;
		assert.ok(selector !== undefined);
		const version = overrides[selector];
		assert.ok(typeof version === "string");
		const [major] = version.split(".");
		assert.ok(major !== undefined);
		const versions = `${major}.x`;
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
		lastUngroupedRule !== -1,
		"high-risk updates must be ungrouped before toolchain groups reinstate them",
	);
	for (const [depName, groupName] of [
		["node", "Node.js toolchain"],
		["pnpm", "pnpm toolchain"],
		["ghcr.io/pnpm/pnpm", "pnpm toolchain"],
	] as const) {
		const index = rules.findLastIndex(
			(rule) => Array.isArray(rule.matchDepNames) && rule.matchDepNames.includes(depName),
		);
		assert.equal(rules[index]?.groupName, groupName, `${depName} must be grouped as ${groupName}`);
		assert.equal(rules[index].matchUpdateTypes, undefined, `${depName} groups every update type`);
		assert.ok(index > lastUngroupedRule, `${depName} must follow the high-risk update rules`);
	}
	// The Pi SDK and the overrides that hold its own packages at its version are one update.
	const pi = rules.findLastIndex((rule) => rule.groupName === "Pi SDK");
	assert.deepEqual(rules[pi]?.matchPackageNames, ["@earendil-works/**"]);
	assert.equal(rules[pi].matchUpdateTypes, undefined, "the Pi SDK groups every update type");
	assert.ok(pi > lastUngroupedRule, "the Pi SDK must follow the high-risk update rules");
});

void test("routine update groups preserve repository boundaries", () => {
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	const groups = [
		["repository tooling dependencies", ["npm"], ["package.json"]],
		[
			"webapp and extension dependencies",
			["npm"],
			["webapp/package.json", "extension/package.json"],
		],
		["server dependencies", ["gradle", "gradle-wrapper"], undefined],
		["AI SDK and practice runtime", ["npm"], ["docker/agents/practice-scripts/package.json"]],
		["CI and build tooling", undefined, [".github/**"]],
	] as const;
	for (const [groupName, managers, fileNames] of groups) {
		const rule = rules.find((candidate) => candidate.groupName === groupName);
		assert.ok(rule, `${groupName} must be a group`);
		assert.deepEqual(rule.matchManagers, managers);
		assert.deepEqual(rule.matchFileNames, fileNames);
		assert.equal(rule.matchDepTypes, undefined);
		assert.ok(Array.isArray(rule.matchUpdateTypes));
		assert.deepEqual(
			new Set(rule.matchUpdateTypes),
			new Set(["minor", "patch", "digest", "pin", "pinDigest"]),
		);
	}
});

void test("what the chart and the release image set ship moves as its own group", () => {
	assert.ok(Array.isArray(config.packageRules));
	const rule = config.packageRules
		.filter(isRecord)
		.find(
			(candidate) =>
				candidate.groupName === "runtime images" && Array.isArray(candidate.matchFileNames),
		);
	assert.ok(rule);
	assert.deepEqual(rule.matchDatasources, ["docker"]);
	assert.ok(
		Array.isArray(rule.matchFileNames) &&
			rule.matchFileNames.includes("security/release-images.json"),
	);
});

void test("updates that need a port or a workflow decision wait for a maintainer on the dashboard", () => {
	assert.ok(Array.isArray(config.packageRules));
	const rules = config.packageRules.filter(isRecord);
	const gated = (match: (rule: Record<string, unknown>) => boolean) =>
		rules.some((rule) => rule.dependencyDashboardApproval === true && match(rule));
	assert.ok(
		gated(
			(rule) =>
				Array.isArray(rule.matchFileNames) &&
				rule.matchFileNames.includes("docker/agents/practice-scripts/package.json"),
		),
	);
	assert.ok(
		gated(
			(rule) =>
				Array.isArray(rule.matchPackageNames) && rule.matchPackageNames.includes("@ai-sdk/**"),
		),
	);
	assert.ok(
		gated(
			(rule) =>
				Array.isArray(rule.matchDepNames) && rule.matchDepNames.includes("zizmorcore/zizmor"),
		),
	);
});

void test("an update that waits for approval does not hold the CI updates grouped with it", () => {
	assert.ok(Array.isArray(config.packageRules));
	const zizmor = config.packageRules
		.filter(isRecord)
		.find(
			(rule) =>
				Array.isArray(rule.matchDepNames) && rule.matchDepNames.includes("zizmorcore/zizmor"),
		);
	assert.equal(zizmor?.groupName, "zizmor");
});

void test("vulnerability remediation bypasses normal update latency", () => {
	assert.equal(config.osvVulnerabilityAlerts, true);
	assert.equal(config.dependencyDashboardOSVVulnerabilitySummary, "unresolved");
	assert.deepEqual(config.vulnerabilityAlerts, {
		rangeStrategy: "replace",
		enabled: true,
		minimumReleaseAge: null,
		dependencyDashboardApproval: false,
		automerge: false,
		groupName: null,
		labels: ["security", "dependencies"],
	});
});

/** The datasources a manager's match strings extract from a file, through their `datasource` group. */
function extractedDatasources(matchStrings: string[], content: string): string[] {
	const datasources: string[] = [];
	for (const pattern of matchStrings) {
		for (const match of content.matchAll(new RegExp(pattern, "gmu"))) {
			const datasource = match.groups?.datasource;
			if (isSet(datasource)) {
				datasources.push(datasource);
			}
		}
	}
	return datasources;
}

void test("every custom manager reads every file it claims to read", async () => {
	const sources = new Map<string, string[]>([
		["Track platform chart versions", ["deploy/platform/k3s-platform.yaml"]],
		["Track platform image tags and digests", ["deploy/platform/k3s-platform.yaml"]],
		["Track the one-host k3s release", ["deploy/platform/k3s-platform.yaml"]],
		["Track the agent-sandbox controller release", ["deploy/platform/k3s-agent-sandbox.yaml"]],
		["Track Dockerfile ARG version pins", ["docker/agents/pi/Dockerfile", "webapp/Dockerfile"]],
		[
			"Track release security tool versions",
			[".github/ci-tools/cosign.json", ".github/ci-tools/syft.json", ".github/ci-tools/trivy.json"],
		],
		["Track the Zizmor CLI version", [".github/workflows/cicd.yml"]],
		["Track the pack CLI version", [".github/workflows/reusable-docker-build.yml"]],
		["Track the buildpacks run image", [".github/workflows/cicd.yml"]],
		[
			"Track the builder and buildpack images in the project descriptor",
			["server/application/project.toml"],
		],
		["Track the Node.js pin in devEngines", ["package.json"]],
		["Track the pnpm pin in devEngines", ["package.json"]],
		["Track release image tags and digests", ["security/release-images.json"]],
		["Track the isolated Semgrep scanner image", [".github/workflows/semgrep.yml"]],
		["Track the TruffleHog scanner image", [".github/workflows/ci-security-scan.yml"]],
		[
			"Track the Kubernetes node images of the cluster legs",
			[".github/workflows/ci-kubernetes.yml"],
		],
	]);
	assert.ok(Array.isArray(config.customManagers));
	assert.ok(config.customManagers.every(isRecord));
	assert.deepEqual(
		new Set(config.customManagers.map((manager) => manager.description)),
		new Set(sources.keys()),
	);
	for (const manager of config.customManagers) {
		if (typeof manager.description !== "string") {
			throw new TypeError("manager description");
		}
		const { description } = manager;
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
						new RegExp(pattern.slice(1, -1), "u").test(file),
				),
				`${description} does not select ${file}`,
			);
			const content = await readFile(file, "utf8");
			assert.ok(
				manager.matchStrings.some((pattern) => new RegExp(pattern, "mu").test(content)),
				`${description} no longer extracts a dependency from ${file}`,
			);
			// Only dependencies this manager extracts matter; a file may have several managers.
			for (const datasource of extractedDatasources(manager.matchStrings, content)) {
				datasources.add(datasource);
			}
		}
		if ([...datasources].some((datasource) => RELEASE_TAG_DATASOURCES.has(datasource))) {
			assert.ok(
				manager.extractVersionTemplate === "^v(?<version>.*)$" ||
					manager.matchStrings.some((pattern) => pattern.includes("(?<extractVersion>")),
				`${description} reads release tags without stripping their v prefix`,
			);
		}
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
	assert.match(String(bump.prBodyNotes), /sync:toolchain-pins/u);
});
