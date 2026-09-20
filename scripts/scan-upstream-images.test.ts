import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, test } from "node:test";

import { planUpstreamSubjects } from "./scan-upstream-images.ts";

const DIGEST = `sha256:${"a".repeat(64)}`;

void describe("planUpstreamSubjects", () => {
	void test("names each pinned image by its digest, never by its tag", () => {
		const subjects = planUpstreamSubjects({
			upstream: [
				{ digest: DIGEST, name: "alpine", repository: "docker.io/library/alpine", tag: "3" },
			],
		});
		assert.deepEqual(subjects, [
			{
				image: "alpine",
				indexDigest: DIGEST,
				reference: `docker.io/library/alpine@${DIGEST}`,
				repository: "docker.io/library/alpine",
			},
		]);
	});

	void test("covers every upstream image in the committed inventory", async () => {
		const inventory: unknown = JSON.parse(await readFile("security/release-images.json", "utf8"));
		const subjects = planUpstreamSubjects(inventory);
		assert.deepEqual(subjects.map((subject) => subject.image).toSorted(), [
			"alpine",
			"nats",
			"nginx",
			"traefik",
		]);
		// A tag would resolve to whatever it points at today rather than to the artefact the release
		// promotes, which is the whole reason the inventory pins digests.
		for (const subject of subjects) {
			assert.match(subject.reference, /@sha256:[a-f0-9]{64}$/u);
		}
	});

	void test("rejects an inventory that could name something other than a pinned image", () => {
		const entry = { digest: DIGEST, name: "alpine", repository: "docker.io/library/alpine" };
		assert.throws(() => planUpstreamSubjects({}), /must be an array/u);
		assert.throws(() => planUpstreamSubjects({ upstream: [] }), /no upstream images/u);
		assert.throws(
			() => planUpstreamSubjects({ upstream: [{ ...entry, name: "al pine" }] }),
			/malformed upstream image name/u,
		);
		assert.throws(
			() => planUpstreamSubjects({ upstream: [{ ...entry, digest: "3" }] }),
			/malformed upstream image digest/u,
		);
		assert.throws(
			() => planUpstreamSubjects({ upstream: [{ ...entry, repository: 7 }] }),
			/must be a string/u,
		);
	});
});
