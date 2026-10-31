import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import {
	releaseImages,
	validateInventory,
	deployedImageConsumers,
	validateChartConsumers,
} from "./check-release-image-inventory.ts";
import { topologyImage } from "./lib/deploy-topology.ts";

await test("repository release builds are covered by the evidence inventory", () => {
	const inventory = JSON.parse(readFileSync("security/release-images.json", "utf8")) as unknown;
	const workflow = readFileSync(".github/workflows/reusable-docker-build.yml", "utf8");
	assert.deepEqual(validateInventory(inventory, workflow), []);
	// Every Dockerfile image is a bake target that the workflow can select.
	const selected = new Set(releaseImages(workflow));
	const targets = [
		...readFileSync("docker/docker-bake.hcl", "utf8").matchAll(
			/^target "(?<name>[a-z0-9-]+)" \{/gmu,
		),
	]
		.flatMap((match) => match.groups?.name ?? [])
		.filter((name) => name !== "image");
	assert.ok(targets.length > 0);
	for (const target of targets) {
		assert.ok(selected.has(target), `${target} is a bake target the workflow cannot select`);
	}
});

await test("the shipped inventory declares the schema version the release validator requires", () => {
	const inventory: unknown = JSON.parse(readFileSync("security/release-images.json", "utf8"));
	assert.ok(typeof inventory === "object" && inventory !== null && "schemaVersion" in inventory);
	assert.equal(inventory.schemaVersion, 1);
});

await test("rejects missing and duplicate inventory entries", () => {
	assert.deepEqual(
		validateInventory({ images: ["server"], upstream: [] }, `  IMAGE_NAMES: '["server","new"]'`),
		["new"],
	);
	assert.throws(
		() =>
			validateInventory(
				{ images: ["server", "server"], upstream: [] },
				`  IMAGE_NAMES: '["server"]'`,
			),
		/duplicates/u,
	);
	assert.throws(() => validateInventory({ images: [], upstream: [{}] }, ""), /malformed upstream/u);
	// A workflow that selects nothing is a broken parse, not a covered inventory.
	assert.throws(
		() => validateInventory({ images: ["server"], upstream: [] }, ""),
		/selects no images/u,
	);
});

await test("rendered chart consumers reject unlocked images and include the sandbox input", () => {
	assert.throws(
		() =>
			deployedImageConsumers([
				{
					name: "worker",
					image: "example.invalid/worker:latest",
					environment: new Map(),
					mounts: [],
				},
			]),
		/bypasses/u,
	);
	assert.throws(() => deployedImageConsumers([]), /no image consumers/u);
	assert.deepEqual(
		deployedImageConsumers([
			{
				name: "worker",
				image: topologyImage("application-server"),
				environment: new Map([["HEPHAESTUS_AGENT_IMAGE_REFERENCE", topologyImage("agent-pi")]]),
				mounts: [],
			},
		]),
		["agent-pi", "application-server"],
	);
});
await test("every chart image needs a schema key, a consumer and release evidence", () => {
	const names = ["agent-pi", "application-server", "nats", "postgres", "webapp"];
	const inventory = new Set(names);
	assert.doesNotThrow(() => validateChartConsumers(names, names, inventory));
	for (const field of ["consumer", "schema", "evidence"]) {
		assert.throws(
			() =>
				validateChartConsumers(
					field === "consumer" ? names.slice(1) : names,
					field === "schema" ? names.slice(1) : names,
					field === "evidence" ? new Set(names.slice(1)) : inventory,
				),
			new RegExp(`missing ${field}`, "u"),
		);
	}
	assert.throws(
		() => validateChartConsumers(names, names, new Set([...names, "unexpected"])),
		/unexpected/u,
	);
});

await test("upstream duplicate names cannot hide behind first-party inventory entries", () => {
	const pin = {
		name: "server",
		repository: "docker.io/library/server",
		digest: `sha256:${"a".repeat(64)}`,
	};
	assert.throws(
		() => validateInventory({ images: ["server"], upstream: [pin] }, `  IMAGE_NAMES: '["server"]'`),
		/duplicates/u,
	);
	assert.throws(
		() => validateInventory({ images: [], upstream: [pin, pin] }, `  IMAGE_NAMES: '["server"]'`),
		/duplicates/u,
	);
});
