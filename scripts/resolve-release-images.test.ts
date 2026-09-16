import assert from "node:assert/strict";
import { describe, test } from "node:test";

import {
	digestOutputs,
	formatResolvedImages,
	parseResolvedImages,
	resolveReleaseImages,
} from "./resolve-release-images.ts";

const DIGEST = `sha256:${"a".repeat(64)}`;
const OTHER = `sha256:${"b".repeat(64)}`;
const subject = {
	image: "webapp",
	reference: "ghcr.io/hephaestus-build/webapp:run-1-1",
	repository: "ghcr.io/hephaestus-build/webapp",
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
			{ image: "webapp", indexDigest: DIGEST, repository: "ghcr.io/hephaestus-build/webapp" },
		]);
	});

	void test("retries a manifest the registry has not published yet", async () => {
		let attempts = 0;
		const images = await resolveReleaseImages([subject], {
			attempts: 3,
			delayMs: 0,
			inspect: async () => {
				attempts += 1;
				return attempts < 3
					? Promise.reject(new Error("MANIFEST_UNKNOWN"))
					: Promise.resolve(DIGEST);
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
		{ image: "webapp", indexDigest: DIGEST, repository: "ghcr.io/hephaestus-build/webapp" },
		{ image: "postgres", indexDigest: OTHER, repository: "ghcr.io/hephaestus-build/postgres" },
	];

	void test("round-trips image, repository and digest", () => {
		assert.deepEqual(parseResolvedImages(formatResolvedImages(images)), images);
	});

	void test("rejects a line the generator would otherwise read as a subject", () => {
		for (const line of [
			"webapp\tghcr.io/hephaestus-build/webapp\n",
			`webapp\t\t${DIGEST}\n`,
			"webapp\tghcr.io/hephaestus-build/webapp\tlatest\n",
			`webapp\tghcr.io/hephaestus-build/webapp\t${DIGEST.toUpperCase()}\n`,
		]) {
			assert.throws(() => parseResolvedImages(line), /malformed resolved release image/u);
		}
	});

	void test("names a step output for every image, not only the ones a job reads today", () => {
		assert.equal(digestOutputs(images), `webapp-digest=${DIGEST}\npostgres-digest=${OTHER}\n`);
	});
});
