import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import {
	releaseImages,
	validateInventory,
	deployedImageConsumers,
	validateChartConsumers,
	composeImageConsumers,
	validateRetained,
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
	const inventory = new Set([...names, "traefik", "alpine"]);
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
		() => validateChartConsumers([...names, "unexpected"], names, inventory),
		/unexpected/u,
	);
});

await test("retained Compose consumers refuse literal, defaulted, missing and unlisted images", () => {
	const known = new Set(["traefik"]);
	const locked = `services:\n  proxy:\n    image: \${HEPHAESTUS_IMAGE_TRAEFIK:?lock required}\n`;
	assert.deepEqual([...composeImageConsumers(locked, "proxy.yaml", known)], ["traefik"]);
	for (const image of [
		"traefik:latest",
		`\${HEPHAESTUS_IMAGE_TRAEFIK:-fallback}`,
		`\${HEPHAESTUS_IMAGE_UNKNOWN:?lock}`,
		"",
	]) {
		assert.throws(
			() =>
				composeImageConsumers(
					`services:\n  proxy:\n    image: ${JSON.stringify(image)}\n`,
					"proxy.yaml",
					known,
				),
			/bypasses/u,
		);
	}
	assert.throws(() => composeImageConsumers("services: {}", "empty.yaml", known), /no services/u);
	assert.throws(() => composeImageConsumers("services: [", "invalid.yaml", known));
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

await test("an inventory entry without either a chart or retained consumer is refused", () => {
	const known = new Set(["webapp", "traefik"]);
	assert.doesNotThrow(() => validateRetained(known, ["webapp"], new Set(["traefik"])));
	assert.throws(
		() => validateRetained(known, ["webapp"], new Set()),
		/traefik.*no evidenced deployment consumer/u,
	);
});
await test("retained services read image pins through YAML merges", () => {
	const text = `x-base: &base
  image: \${HEPHAESTUS_IMAGE_TRAEFIK:?lock required}
services:
  proxy:
    <<: *base
  second:
    image: \${HEPHAESTUS_IMAGE_ALPINE:?lock required}
`;
	assert.deepEqual(
		[...composeImageConsumers(text, "merged.yaml", new Set(["traefik", "alpine"]))],
		["traefik", "alpine"],
	);
});
