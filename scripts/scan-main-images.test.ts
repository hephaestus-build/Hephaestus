import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, test } from "node:test";

import { isImageIndex, PLATFORM, reportStem, selectPlatformDigest } from "./lib/image-scan.ts";
import { planSubjects } from "./scan-main-images.ts";

const DIGEST = `sha256:${"a".repeat(64)}`;
const ARM_DIGEST = `sha256:${"b".repeat(64)}`;

void describe("planSubjects", () => {
	void test("names every inventory image at the requested registry and tag", () => {
		const subjects = planSubjects(
			{ images: ["webapp", "postgres"] },
			"ghcr.io/hephaestus-build",
			"main",
		);
		assert.deepEqual(subjects, [
			{
				image: "webapp",
				reference: "ghcr.io/hephaestus-build/webapp:main",
				repository: "ghcr.io/hephaestus-build/webapp",
			},
			{
				image: "postgres",
				reference: "ghcr.io/hephaestus-build/postgres:main",
				repository: "ghcr.io/hephaestus-build/postgres",
			},
		]);
	});

	void test("covers the committed release image inventory", async () => {
		const inventory: unknown = JSON.parse(await readFile("security/release-images.json", "utf8"));
		const subjects = planSubjects(inventory, "ghcr.io/hephaestus-build", "main");
		// The four first-party images the release promotes. The upstream images in the same file are
		// not built here and have no `:main` tag, so they are scanned by their pinned digest in
		// scan-upstream-images.ts instead.
		assert.deepEqual(subjects.map((subject) => subject.image).toSorted(), [
			"agent-pi",
			"application-server",
			"postgres",
			"webapp",
		]);
	});

	void test("rejects an inventory that could name something other than an image", () => {
		assert.throws(() => planSubjects({ images: ["web app"] }, "ghcr.io/x", "main"), /malformed/);
		assert.throws(() => planSubjects({ images: [] }, "ghcr.io/x", "main"), /no images/);
		assert.throws(() => planSubjects({ images: [7] }, "ghcr.io/x", "main"), /must be a string/);
		assert.throws(() => planSubjects({}, "ghcr.io/x", "main"), /must be an array/);
	});
});

void describe("selectPlatformDigest", () => {
	void test("picks the requested architecture out of a multi-architecture index", () => {
		const raw = {
			manifests: [
				{ digest: ARM_DIGEST, platform: { architecture: "arm64", os: "linux" } },
				{ digest: DIGEST, platform: { architecture: "amd64", os: "linux" } },
			],
		};
		assert.equal(selectPlatformDigest(raw, PLATFORM), DIGEST);
	});

	void test("ignores the attestation manifest a multi-architecture push also publishes", () => {
		const raw = {
			manifests: [
				{
					digest: `sha256:${"c".repeat(64)}`,
					platform: { architecture: "unknown", os: "unknown" },
				},
				{ digest: DIGEST, platform: { architecture: "amd64", os: "linux" } },
			],
		};
		assert.equal(selectPlatformDigest(raw, PLATFORM), DIGEST);
	});

	void test("returns undefined for a single manifest so the caller asks for its digest", () => {
		assert.equal(selectPlatformDigest({ config: {}, layers: [] }, PLATFORM), undefined);
	});

	void test("returns undefined rather than a wrong architecture when the index lacks one", () => {
		const raw = {
			manifests: [{ digest: ARM_DIGEST, platform: { architecture: "arm64", os: "linux" } }],
		};
		assert.equal(selectPlatformDigest(raw, PLATFORM), undefined);
	});

	void test("skips entries that carry no usable platform", () => {
		const raw = {
			manifests: [
				null,
				{ digest: ARM_DIGEST },
				{ platform: { architecture: "amd64", os: "linux" } },
				{ digest: DIGEST, platform: { architecture: "amd64", os: "linux" } },
			],
		};
		assert.equal(selectPlatformDigest(raw, PLATFORM), DIGEST);
	});

	void test("rejects a document that is not a manifest at all", () => {
		assert.throws(() => selectPlatformDigest("nope", PLATFORM), /must be a JSON object/);
	});
});

void describe("isImageIndex", () => {
	void test("malformed index declarations never become a single-manifest fallback", () => {
		for (const manifests of [null, {}, "invalid", 42]) {
			assert.throws(() => isImageIndex({ manifests }), /must be an array/);
		}
	});

	// The two ways selectPlatformDigest returns undefined mean opposite things, and only this tells
	// them apart: a single manifest is asked for its own digest, while an index missing the platform
	// must fail. Falling back to the index digest there would hand Trivy a multi-platform reference,
	// which it resolves against the host — an amd64 scan filed as arm64 evidence.
	void test("separates a multi-platform index from a single manifest", () => {
		assert.equal(
			isImageIndex({
				manifests: [{ digest: DIGEST, platform: { architecture: "amd64", os: "linux" } }],
			}),
			true,
		);
		assert.equal(isImageIndex({ manifests: [] }), true);
		assert.equal(isImageIndex({ config: {}, layers: [] }), false);
	});

	void test("rejects a document that is not a manifest at all", () => {
		assert.throws(() => isImageIndex("nope"), /must be a JSON object/);
	});
});

void test("reportStem names files after the image and platform", () => {
	assert.equal(reportStem("webapp", PLATFORM), "webapp-linux-amd64");
});
