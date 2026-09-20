import assert from "node:assert/strict";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { readInventory, resolveImages } from "./commit-image-lock.ts";
import { bootedBuild, smokeLockImages } from "./prepare-smoke-lock.ts";

const inventory = await readInventory(
	fileURLToPath(new URL("../security/release-images.json", import.meta.url)),
);
const commit = "c".repeat(40);
const applicationDigest = `sha256:${"1".repeat(64)}`;

await test("a render sets every image a commit deploy would pin, to a name no registry serves", async () => {
	const rendered = smokeLockImages(inventory);
	const pinned = await resolveImages(inventory, commit, "o", async () => applicationDigest);
	assert.deepEqual(Object.keys(rendered).toSorted(), Object.keys(pinned).toSorted());
	for (const [name, reference] of Object.entries(rendered)) {
		assert.match(reference, /^example\.invalid\/[a-z-]+@sha256:0{64}$/u, name);
	}
});

await test("a boot runs this run's own application server and database beside the upstream pins", () => {
	const booted = smokeLockImages(inventory, { commit, applicationDigest });
	assert.equal(
		booted.HEPHAESTUS_IMAGE_APPLICATION_SERVER,
		`ghcr.io/hephaestus-build/application-server@${applicationDigest}`,
	);
	assert.equal(booted.HEPHAESTUS_IMAGE_POSTGRES, `ghcr.io/hephaestus-build/postgres:${commit}`);
	for (const upstream of inventory.upstream) {
		if (upstream.name === "alpine" || upstream.name === "nats") {
			assert.equal(
				booted[`HEPHAESTUS_IMAGE_${upstream.name.toUpperCase()}`],
				`${upstream.repository}@${upstream.digest}`,
			);
		}
	}
	// The edge and the webapp are rendered but never started, so nothing has to exist for them.
	for (const name of ["WEBAPP", "AGENT_PI", "NGINX", "TRAEFIK"]) {
		assert.match(String(booted[`HEPHAESTUS_IMAGE_${name}`]), /^example\.invalid\//u);
	}
});

await test("a booted build is named whole or not at all", () => {
	assert.equal(bootedBuild({}), undefined);
	assert.deepEqual(bootedBuild({ HEAD_SHA: commit, APPLICATION_DIGEST: applicationDigest }), {
		commit,
		applicationDigest,
	});
	assert.throws(() => bootedBuild({ HEAD_SHA: commit }), /together/u);
	assert.throws(() => bootedBuild({ APPLICATION_DIGEST: applicationDigest }), /together/u);
	assert.throws(
		() => bootedBuild({ HEAD_SHA: "main", APPLICATION_DIGEST: applicationDigest }),
		/full commit SHA/u,
	);
	assert.throws(
		() => bootedBuild({ HEAD_SHA: commit, APPLICATION_DIGEST: "latest" }),
		/invalid application image digest/u,
	);
});
