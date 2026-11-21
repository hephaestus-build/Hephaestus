import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { parse } from "yaml";

import { pathFilters } from "./check-affected.ts";
import { isRecord } from "./lib/json.ts";

const source = readFileSync(".github/workflows/cicd.yml", "utf8");
const record = (value: unknown, what: string): Record<string, unknown> => {
	assert.ok(isRecord(value), what);
	return value;
};

void test("a merge group that changes the server image starts that image and its own PostgreSQL image", () => {
	const jobs = record(record(parse(source), "cicd.yml").jobs, "jobs");
	const outputs = record(record(jobs["detect-changes"], "detect-changes").outputs, "outputs");
	assert.match(String(outputs["queue-server-image"]), /github\.event_name == 'merge_group'/u);
	for (const filter of ["server-image", "build-config", "kubernetes"]) {
		assert.match(
			String(outputs["queue-server-image"]),
			new RegExp(`outputs\\.${filter} == 'true'`, "u"),
		);
	}

	// The check's own files run it.
	const serverImage = pathFilters(source)["server-image"] ?? [];
	for (const file of [
		"scripts/server-image-startup.ts",
		"scripts/lib/buildpack-descriptor.ts",
		"scripts/lib/prometheus-text.ts",
		"scripts/kubernetes-chart-render-support.ts",
	]) {
		assert.ok(serverImage.includes(file), `${file} selects the server image`);
	}

	const job = record(jobs["Server-image"], "Server-image");
	assert.deepEqual(job.needs, ["detect-changes", "Docker"]);
	for (const condition of [
		/needs\.detect-changes\.outputs\.queue-server-image == 'true'/u,
		/needs\.Docker\.outputs\.server-digest != ''/u,
		/needs\.Docker\.outputs\.postgres-digest != ''/u,
	]) {
		assert.match(String(job.if), condition);
	}

	// Every platform of the merge group's image index starts on a runner of its own architecture.
	assert.match(String(outputs.platforms), /'\["linux\/amd64","linux\/arm64"\]'/u);
	const strategy = record(job.strategy, "Server-image.strategy");
	// fail-fast-Server-image waits for the whole job, so a failed leg must cancel the other one.
	assert.equal(strategy["fail-fast"], undefined);
	assert.match(
		String(record(strategy.matrix, "Server-image.strategy.matrix").platform),
		/^\$\{\{ fromJSON\(needs\.detect-changes\.outputs\.platforms\) \}\}$/u,
	);
	assert.match(
		String(job["runs-on"]),
		/matrix\.platform == 'linux\/arm64' && 'ubuntu-24\.04-arm'/u,
	);

	// The startup time is a ratio against main's image on the same runner, so the job passes that image.
	assert.ok(Array.isArray(job.steps), "Server-image.steps");
	const startup = job.steps
		.map((step) => record(step, "step"))
		.find((step) => String(step.run).includes("scripts/server-image-startup.ts"));
	const env = record(startup?.env, "startup env");
	assert.match(String(env.BASE_SERVER_IMAGE), /\/server:main$/u);
	assert.match(String(env.PLATFORM), /^\$\{\{ matrix\.platform \}\}$/u);
	// A leg on the wrong architecture would start another platform's image, so it fails first.
	assert.match(
		String(startup?.run),
		/\{\{\.Server\.Os\}\}\/\{\{\.Server\.Arch\}\}'\)" = "\$PLATFORM" \]/u,
	);
	assert.match(
		String(startup?.run),
		/"\$SERVER_IMAGE" "\$POSTGRES_IMAGE" "\$BASE_SERVER_IMAGE"\n$/u,
	);

	// A merge group aliases nothing, so the job needs this group's own PostgreSQL image.
	const docker = record(record(jobs.Docker, "Docker").with, "Docker.with");
	for (const expression of [docker.postgres_image_changed, docker.postgres]) {
		assert.match(
			String(expression),
			/needs\.detect-changes\.outputs\.queue-server-image == 'true'/u,
		);
	}

	const gate = record(jobs["all-ci-passed"], "all-ci-passed");
	assert.ok(Array.isArray(gate.needs) && gate.needs.includes("Server-image"));
	assert.match(
		JSON.stringify(gate.steps),
		/needs\.detect-changes\.outputs\.queue-server-image == 'true' && needs\.Server-image\.result != 'success'/u,
	);
});
