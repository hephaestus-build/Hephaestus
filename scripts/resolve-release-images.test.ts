import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";

import { capturedReleaseImages } from "./resolve-captured-release-images.ts";
import {
	digestOutputs,
	formatResolvedImages,
	parseResolvedImages,
	resolveReleaseImages,
} from "./resolve-release-images.ts";

const DIGEST = `sha256:${"a".repeat(64)}`;
const OTHER = `sha256:${"b".repeat(64)}`;
const subject = {
	image: "agent",
	reference: "ghcr.io/hephaestus-build/agent:run-1-1",
	repository: "ghcr.io/hephaestus-build/agent",
};
const noSleep = async (): Promise<void> => {
	throw new Error("the resolver must not sleep on a first-attempt success");
};

void describe("resolveReleaseImages", () => {
	void test("resolves each image to the index digest the run published", async () => {
		const images = await resolveReleaseImages([subject], {
			inspect: async () => `${DIGEST}\n`,
			sleep: noSleep,
		});
		assert.deepEqual(images, [
			{ image: "agent", indexDigest: DIGEST, repository: "ghcr.io/hephaestus-build/agent" },
		]);
	});

	void test("retries a manifest the registry has not published yet", async () => {
		let attempts = 0;
		const images = await resolveReleaseImages([subject], {
			attempts: 3,
			delayMs: 0,
			inspect: async () => {
				attempts += 1;
				if (attempts < 3) {
					throw new Error("MANIFEST_UNKNOWN");
				}
				return DIGEST;
			},
		});
		assert.equal(attempts, 3);
		assert.equal(images[0]?.indexDigest, DIGEST);
	});

	void test("fails rather than resolving an image to something that is not a digest", async () => {
		// An incomplete or malformed subject set is exactly what the evidence gate exists to catch,
		// so it must never be reached by quietly dropping an image the registry would not answer for.
		await assert.rejects(
			resolveReleaseImages([subject], {
				attempts: 2,
				delayMs: 0,
				inspect: async () => "latest",
			}),
			/could not resolve an index digest/u,
		);
		await assert.rejects(
			resolveReleaseImages([subject], {
				attempts: 2,
				delayMs: 0,
				inspect: async () => {
					throw new Error("unauthorized");
				},
			}),
			/could not resolve an index digest/u,
		);
	});
});

void describe("the resolver's hand-off to the evidence generator", () => {
	const images = [
		{ image: "agent", indexDigest: DIGEST, repository: "ghcr.io/hephaestus-build/agent" },
		{ image: "postgres", indexDigest: OTHER, repository: "ghcr.io/hephaestus-build/postgres" },
	];

	void test("round-trips image, repository and digest", () => {
		assert.deepEqual(parseResolvedImages(formatResolvedImages(images)), images);
	});

	void test("rejects a line the generator would otherwise read as a subject", () => {
		for (const line of [
			"agent\tghcr.io/hephaestus-build/agent\n",
			`agent\t\t${DIGEST}\n`,
			"agent\tghcr.io/hephaestus-build/agent\tlatest\n",
			`agent\tghcr.io/hephaestus-build/agent\t${DIGEST.toUpperCase()}\n`,
		]) {
			assert.throws(() => parseResolvedImages(line), /malformed resolved release image/u);
		}
	});

	void test("names a step output for every image, not only the ones a job reads today", () => {
		assert.equal(digestOutputs(images), `agent-digest=${DIGEST}\npostgres-digest=${OTHER}\n`);
	});
});

void test("captured release images retain the exact inventory and digests without a tag lookup", () => {
	assert.deepEqual(capturedReleaseImages([subject], JSON.stringify({ agent: OTHER })), [
		{ image: "agent", repository: subject.repository, indexDigest: OTHER },
	]);
	for (const input of [
		"{}",
		"null",
		"[]",
		'{"agent":""}',
		'{"agent":"latest"}',
		JSON.stringify({ agent: DIGEST, unexpected: OTHER }),
		"invalid",
	]) {
		assert.throws(() => capturedReleaseImages([subject], input));
	}
});

void test("the captured-image CLI writes only complete producer digests without a registry command", async (context) => {
	const directory = await mkdtemp(path.join(tmpdir(), "captured-release-images-"));
	context.after(async () => rm(directory, { recursive: true, force: true }));
	const output = path.join(directory, "images.tsv");
	const images = ["server", "agent", "postgres"];
	const digests = Object.fromEntries(images.map((image) => [image, DIGEST]));
	const run = (input: string) =>
		spawnSync(process.execPath, ["scripts/resolve-captured-release-images.ts", output], {
			encoding: "utf8",
			env: { ...process.env, PATH: "", RELEASE_IMAGE_DIGESTS: input },
		});
	const valid = run(JSON.stringify(digests));
	assert.equal(valid.status, 0, valid.stderr);
	assert.deepEqual(
		parseResolvedImages(await readFile(output, "utf8")).toSorted((a, b) =>
			a.image.localeCompare(b.image),
		),
		images.toSorted().map((image) => ({
			image,
			repository: `ghcr.io/hephaestus-build/${image}`,
			indexDigest: DIGEST,
		})),
	);
	for (const input of ["", "{}", JSON.stringify({ ...digests, postgres: "" })]) {
		const refused = run(input);
		assert.notEqual(refused.status, 0);
		assert.doesNotMatch(refused.stderr, /spawn docker|ENOENT/u);
	}
});
