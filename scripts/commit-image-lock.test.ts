import assert from "node:assert/strict";
import { test } from "node:test";

import { environmentKey, parseInventory, resolveImages } from "./commit-image-lock.ts";

const commit = "c".repeat(40);
const digest = `sha256:${"1".repeat(64)}`;
const resolver = async () => digest;

await test("an upstream image is pinned as the commit pins it, not looked up", async () => {
	const inventory = parseInventory({
		images: [],
		upstream: [{ name: "nginx", repository: "docker.io/library/nginx", digest }],
	});
	const images = await resolveImages(inventory, commit, "o", async () => {
		throw new Error("upstream must not be resolved from a registry");
	});
	assert.equal(images.HEPHAESTUS_IMAGE_NGINX, `docker.io/library/nginx@${digest}`);
});

await test("a first-party image is taken from this commit's own build", async () => {
	const seen: string[] = [];
	const images = await resolveImages(
		parseInventory({ images: ["server"], upstream: [] }),
		commit,
		"hephaestus-build",
		async (repository, at) => {
			seen.push(`${repository}@${at}`);
			return digest;
		},
	);
	assert.deepEqual(seen, [`ghcr.io/hephaestus-build/server@${commit}`]);
	assert.equal(images.HEPHAESTUS_IMAGE_SERVER, `ghcr.io/hephaestus-build/server@${digest}`);
});

await test("anything that is not a digest is refused rather than deployed", async () => {
	const inventory = parseInventory({ images: ["webapp"], upstream: [] });
	await assert.rejects(
		resolveImages(inventory, commit, "o", async () => "main"),
		/did not resolve to a digest/u,
	);
	await assert.rejects(resolveImages(inventory, "abc", "o", resolver), /expected a full commit/u);
	assert.throws(
		() =>
			parseInventory({ images: [], upstream: [{ name: "n", repository: "r", digest: "latest" }] }),
		/not pinned by digest/u,
	);
});

await test("the image environment key uses the canonical image name", () => {
	assert.equal(environmentKey("server"), "HEPHAESTUS_IMAGE_SERVER");
	assert.equal(environmentKey("agent"), "HEPHAESTUS_IMAGE_AGENT");
});
