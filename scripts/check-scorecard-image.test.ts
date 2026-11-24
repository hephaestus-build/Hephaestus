import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
	actionImage,
	type Manifest,
	type Pins,
	pinsOf,
	type Registry,
	verify,
	WORKFLOW,
} from "./check-scorecard-image.ts";

const digest = (hex: string) => `sha256:${hex.repeat(64)}`;
const PINNED = digest("a");
const CONFIG = digest("c");
const LAYER = digest("1");
const pins: Pins = {
	commit: "f".repeat(40),
	repository: "ghcr.io/ossf/scorecard-action",
	tag: "v2.5.0",
	digest: PINNED,
};
const actionYaml =
	'runs:\n  using: "docker"\n  image: "docker://ghcr.io/ossf/scorecard-action:v2.5.0"\n';
const image = { config: { digest: CONFIG }, layers: [{ digest: LAYER }] };

const AMD64_BLOBS: Record<string, unknown> = {
	[CONFIG]: { os: "linux", architecture: "amd64" },
	[LAYER]: {},
};

function registry(manifests: Record<string, Manifest>, blobs = AMD64_BLOBS): Registry {
	return {
		manifest: async (reference) => manifests[reference],
		blob: async (blobDigest) => blobs[blobDigest],
		hasBlob: async (blobDigest) => blobDigest in blobs,
	};
}

const single = {
	[pins.tag]: { digest: PINNED, body: image },
	[PINNED]: { digest: PINNED, body: image },
};

void test("the pinned single-platform image that the action names passes", async () => {
	assert.deepEqual(await verify(pins, actionYaml, registry(single)), []);
});

void test("an index passes through its linux/amd64 image", async () => {
	const child = digest("b");
	const index = {
		manifests: [
			{ digest: digest("e"), platform: { os: "linux", architecture: "arm64" } },
			{ digest: child, platform: { os: "linux", architecture: "amd64" } },
		],
	};
	const manifests = {
		[pins.tag]: { digest: PINNED, body: index },
		[PINNED]: { digest: PINNED, body: index },
		[child]: { digest: child, body: image },
	};
	assert.deepEqual(await verify(pins, actionYaml, registry(manifests)), []);
});

void test("an index whose linux/amd64 manifest the registry does not serve fails", async () => {
	const child = digest("b");
	const index = {
		manifests: [{ digest: child, platform: { os: "linux", architecture: "amd64" } }],
	};
	const manifests = {
		[pins.tag]: { digest: PINNED, body: index },
		[PINNED]: { digest: PINNED, body: index },
	};
	assert.deepEqual(await verify(pins, actionYaml, registry(manifests)), [
		`the registry does not serve the linux/amd64 manifest ${child}`,
	]);
});

void test("a tag that moved, a missing layer or another platform fails", async () => {
	const problems = async (manifests: Record<string, Manifest>, blobs?: Record<string, unknown>) => {
		const found = await verify(pins, actionYaml, registry(manifests, blobs));
		return found.join("\n");
	};
	const moved = { ...single, [pins.tag]: { digest: digest("d"), body: image } };
	assert.match(await problems(moved), /resolves to sha256:d+, not the pinned/u);
	assert.match(
		await problems(single, { [CONFIG]: { os: "linux", architecture: "amd64" } }),
		/does not serve layer/u,
	);
	assert.match(
		await problems(single, { [CONFIG]: { os: "linux", architecture: "arm64" }, [LAYER]: {} }),
		/is linux\/arm64, not linux\/amd64/u,
	);
});

void test("an action that runs another image fails", async () => {
	const other = actionYaml.replace("v2.5.0", "v2.6.0");
	assert.deepEqual(await verify(pins, other, registry(single)), [
		`ossf/scorecard-action@${pins.commit} runs ghcr.io/ossf/scorecard-action:v2.6.0, not ghcr.io/ossf/scorecard-action:v2.5.0`,
	]);
	assert.equal(actionImage("runs:\n  using: node24\n"), undefined);
});

void test("the workflow pins the action by commit and its image by digest", async () => {
	const pinned = pinsOf(await readFile(WORKFLOW, "utf8"));
	assert.match(pinned.commit, /^[a-f0-9]{40}$/u);
	assert.equal(pinned.repository, "ghcr.io/ossf/scorecard-action");
	assert.match(pinned.digest, /^sha256:[a-f0-9]{64}$/u);
	assert.throws(() => pinsOf("uses: ossf/scorecard-action@v2.5.0\n"), /must pin/u);
});
