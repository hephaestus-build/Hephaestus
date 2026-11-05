import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";

import { parse } from "yaml";

import { pathFilters } from "./check-affected.ts";
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

void test("a merge group whose change reaches a migration runs the release's seeded upgrade on its own images", () => {
	const cicd = workflow("cicd.yml");
	const jobs = record(cicd.jobs, "jobs");
	const filter =
		pathFilters(readFileSync(".github/workflows/cicd.yml", "utf8"))["database-upgrade"] ?? [];
	assert.deepEqual(filter, [
		"server/application/src/main/resources/db/changelog/**",
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/core/database/BaselineSynchronization.java",
	]);
	// Each path also selects the application-server image, which the upgrade installs: a merge group
	// aliases nothing, so a path outside it would leave the upgrade without its candidate.
	const serverImage =
		pathFilters(readFileSync(".github/workflows/cicd.yml", "utf8"))["application-server-image"] ??
		[];
	for (const file of filter) {
		assert.ok(
			serverImage.some(
				(glob) =>
					!glob.startsWith("!") && path.posix.matchesGlob(file.replace("**", "a.xml"), glob),
			),
			file,
		);
	}

	const outputs = record(record(jobs["detect-changes"], "detect-changes").outputs, "outputs");
	assert.match(
		String(outputs["queue-upgrade"]),
		/^\$\{\{ \(github\.event_name == 'merge_group' && steps\.filter\.outputs\.database-upgrade == 'true'\) \}\}$/u,
	);

	const upgrade = record(jobs["Database-upgrade"], "Database-upgrade");
	assert.equal(upgrade.uses, "./.github/workflows/release-upgrade.yml");
	assert.deepEqual(upgrade.needs, ["detect-changes", "Docker"]);
	assert.match(String(upgrade.if), /needs\.detect-changes\.outputs\.queue-upgrade == 'true'/u);
	// Without inputs the reusable workflow resolves the last release and this commit's images.
	assert.equal(upgrade.with, undefined);

	const gate = record(jobs["all-ci-passed"], "all-ci-passed");
	assert.ok(Array.isArray(gate.needs) && gate.needs.includes("Database-upgrade"));
	// A skipped upgrade does not pass a merge group that needed one.
	assert.match(
		JSON.stringify(gate.steps),
		/needs\.detect-changes\.outputs\.queue-upgrade == 'true' && needs\.Database-upgrade\.result != 'success'/u,
	);
	const docker = record(record(jobs.Docker, "Docker").with, "Docker.with");
	assert.match(
		String(docker.postgres_image_changed),
		/needs\.detect-changes\.outputs\.queue-upgrade == 'true'/u,
	);

	const call = record(
		record(workflow("release-upgrade.yml").on, "on").workflow_call,
		"workflow_call",
	);
	for (const [name, input] of Object.entries(record(call.inputs, "inputs"))) {
		assert.equal(record(input, name).required, false, `${name} is optional for the merge group`);
	}
});
