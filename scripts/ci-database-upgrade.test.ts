import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";

import { parse, parseDocument } from "yaml";

import { pathFilters } from "./check-affected.ts";
import { evaluateTierOutput, expandExpressions, jobRuns } from "./ci-contract-support.ts";
import { isRecord } from "./lib/json.ts";

const workflow = (name: string): Record<string, unknown> => {
	const parsed: unknown = parse(readFileSync(`.github/workflows/${name}`, "utf8"));
	assert.ok(isRecord(parsed));
	return parsed;
};
const record = (value: unknown, what: string): Record<string, unknown> => {
	assert.ok(isRecord(value), what);
	return value;
};

void test("a main push or merge group that changes the upgrade inputs runs it on its own images", () => {
	const cicd = workflow("cicd.yml");
	assert.deepEqual(record(record(cicd.on, "on").push, "push").branches, ["main"]);
	const jobs = record(cicd.jobs, "jobs");
	const filter =
		pathFilters(readFileSync(".github/workflows/cicd.yml", "utf8"))["database-upgrade"] ?? [];
	for (const input of [
		".github/workflows/release-upgrade.yml",
		".migration/upgrade-order.md",
		"MIGRATION.md",
		"docker/postgres/Dockerfile",
		"scripts/release-upgrade-test.ts",
		"scripts/resolve-release-upgrade-images.ts",
		"scripts/lib/release-upgrade-prefix.ts",
		"scripts/lib/release-upgrade-fingerprint.ts",
		"scripts/lib/changelog-xml.ts",
		"scripts/lib/cutover-seeds.ts",
		"scripts/lib/database-roles.ts",
		"scripts/lib/migration-guide.ts",
		"scripts/lib/previous-release.ts",
		"server/application/src/main/resources/db/master.xml",
		"server/application/src/main/resources/db/changelog/next/a.xml",
		"server/application/src/main/resources/db/schema-fingerprint.sql",
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/core/database/BaselineSynchronization.java",
		"server/application/src/test/resources/db/cutover-seeds/a.seed.sql",
		"server/application/src/test/resources/db/practice-native/assert-migrated.sql",
		"server/application/src/test/resources/db/practice-native/result-parent.sql",
	]) {
		assert.ok(
			filter.some((glob) => path.posix.matchesGlob(input, glob)),
			`Changes to ${input} must run the upgrade`,
		);
	}
	assert.ok(
		!filter.some((glob) => path.posix.matchesGlob("scripts/release-upgrade-prefix.test.ts", glob)),
	);

	const outputs = record(record(jobs["detect-changes"], "detect-changes").outputs, "outputs");
	for (const event of ["push", "merge_group", "pull_request", "workflow_dispatch"]) {
		for (const changed of ["true", "false"]) {
			assert.equal(
				evaluateTierOutput(String(outputs["seeded-upgrade"]), event, {
					filter: { "database-upgrade": changed },
				}),
				String(changed === "true" && (event === "push" || event === "merge_group")),
				`${event} with upgrade inputs changed=${changed}`,
			);
		}
	}
	assert.match(
		String(outputs["server-image"]),
		/github\.event_name == 'merge_group' && steps\.filter\.outputs\.database-upgrade == 'true'/u,
	);

	const upgrade = record(jobs["Database-upgrade"], "Database-upgrade");
	assert.equal(upgrade.uses, "./.github/workflows/release-upgrade.yml");
	assert.deepEqual(upgrade.needs, ["detect-changes", "Docker"]);
	assert.match(String(upgrade.if), /needs\.detect-changes\.outputs\.seeded-upgrade == 'true'/u);
	// Without inputs the reusable workflow resolves the last release and this commit's images.
	assert.equal(upgrade.with, undefined);

	const gate = record(jobs["all-ci-passed"], "all-ci-passed");
	assert.ok(Array.isArray(gate.needs) && gate.needs.includes("Database-upgrade"));
	// A selected upgrade must pass.
	assert.match(
		JSON.stringify(gate.steps),
		/needs\.detect-changes\.outputs\.seeded-upgrade == 'true' && needs\.Database-upgrade\.result != 'success'/u,
	);
	const docker = record(record(jobs.Docker, "Docker").with, "Docker.with");
	assert.match(
		String(docker.postgres),
		/needs\.detect-changes\.outputs\.seeded-upgrade == 'true'/u,
	);

	const call = record(
		record(workflow("release-upgrade.yml").on, "on").workflow_call,
		"workflow_call",
	);
	for (const [name, input] of Object.entries(record(call.inputs, "inputs"))) {
		assert.equal(record(input, name).required, false, `${name} is optional for the merge group`);
	}
});

void test("a workflow-only drill change packages the server and requires both published images", () => {
	const document = parseDocument(readFileSync(".github/workflows/cicd.yml", "utf8"));
	const event = "merge_group";
	const filter = { "database-upgrade": "true", "ci-config": "true" };
	const output = (name: string): string =>
		evaluateTierOutput(String(document.getIn(["jobs", "detect-changes", "outputs", name])), event, {
			filter,
		});
	const result = "success";
	const candidate = { "server-digest": "sha256:server", "postgres-digest": "sha256:postgres" };
	const needs = {
		"detect-changes": {
			result,
			outputs: {
				"any-code": output("any-code"),
				"ci-config": output("ci-config"),
				"seeded-upgrade": output("seeded-upgrade"),
				"server-image": output("server-image"),
				images: "true",
				publishable: "true",
			},
		},
		Security: { result },
		"vulnerability-database": { result },
		"server-package": { result },
		Docker: { result, outputs: candidate },
	};
	assert.equal(needs["detect-changes"].outputs["any-code"], "false");
	for (const job of ["vulnerability-database", "server-package", "Docker", "Database-upgrade"]) {
		assert.ok(jobRuns(document, job, event, needs), `${job} must run`);
	}
	for (const image of ["server", "postgres"]) {
		const selected = document.getIn(["jobs", "Docker", "with", image]);
		assert.equal(expandExpressions(selected, { needs }), "true", `${image} image must build`);
		assert.ok(
			!jobRuns(document, "Database-upgrade", event, {
				...needs,
				Docker: { result, outputs: { ...candidate, [`${image}-digest`]: "" } },
			}),
			`upgrade cannot run without the ${image} image`,
		);
	}
});
