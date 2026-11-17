import assert from "node:assert/strict";
import { test } from "node:test";

import {
	environmentKey,
	inspectDigest,
	isTransientRegistryFailure,
	parseInventory,
	resolveImages,
} from "./commit-image-lock.ts";

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

const failure = (stderr: string) => Object.assign(new Error("Command failed"), { stderr });

await test("a transient registry read is tried three times over about two minutes", async () => {
	const waits: number[] = [];
	let calls = 0;
	const inspected = await inspectDigest(
		"ghcr.io/o/webapp:c",
		async () => {
			calls += 1;
			if (calls < 3) {
				throw failure(
					"ERROR: content at ghcr.io/o/webapp/manifests/sha256:81 not found: not found",
				);
			}
			return `${digest}\n`;
		},
		async (milliseconds) => waits.push(milliseconds),
	);
	assert.equal(inspected, digest);
	assert.deepEqual(waits, [30_000, 90_000]);

	calls = 0;
	await assert.rejects(
		inspectDigest(
			"ghcr.io/o/webapp:c",
			async () => {
				calls += 1;
				throw failure("httpReadSeeker: failed open: unexpected status code 503");
			},
			async () => undefined,
		),
		/Command failed/u,
	);
	assert.equal(calls, 3);
});

await test("a digest mismatch or another failure is not tried again", async () => {
	for (const stderr of [
		"failed commit on ref: unexpected digest sha256:1 not found, expected sha256:2",
		"ERROR: digest mismatch",
		"ERROR: unauthorized: authentication required",
	]) {
		let calls = 0;
		await assert.rejects(
			inspectDigest(
				"ghcr.io/o/webapp:c",
				async () => {
					calls += 1;
					throw failure(stderr);
				},
				async () => assert.fail("must not wait"),
			),
		);
		assert.equal(calls, 1, stderr);
	}
	assert.equal(isTransientRegistryFailure(new Error("no stderr")), false);
	for (const stderr of ["502 Bad Gateway", "read tcp: connection reset by peer", "i/o timeout"]) {
		assert.equal(isTransientRegistryFailure(failure(stderr)), true, stderr);
	}
});
