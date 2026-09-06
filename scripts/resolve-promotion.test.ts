import assert from "node:assert/strict";
import { test } from "node:test";

import { resolvePromotion, type PromotionSources } from "./resolve-promotion.ts";

const commit = "a".repeat(40);
const images = { HEPHAESTUS_IMAGE_WEBAPP: `ghcr.io/o/webapp@sha256:${"1".repeat(64)}` };
const request = { allowRollback: false, freeze: false, refreshDatabaseImage: false };
const never = (what: string) => (): Promise<never> =>
	Promise.reject(new Error(`${what} must not be consulted`));
const sources: PromotionSources = {
	compare: never("history"),
	isDraft: never("the release"),
	images: never("the registry"),
};

await test("a published release is promoted by tag and reported by version", async () => {
	assert.deepEqual(
		await resolvePromotion(
			{ ...request, release: "v1.2.3", freeze: true },
			{ ...sources, isDraft: () => Promise.resolve(false) },
		),
		{ channel: { release: "v1.2.3", allowRollback: false, freeze: true }, version: "1.2.3" },
	);
});

await test("a draft or a mutable reference is never promoted", async () => {
	await assert.rejects(
		resolvePromotion(
			{ ...request, release: "v1.2.3" },
			{ ...sources, isDraft: () => Promise.resolve(true) },
		),
		/still a draft/,
	);
	for (const release of ["main", "v1.2", "v01.2.3", "v1.2.3-rc.1"])
		await assert.rejects(resolvePromotion({ ...request, release }, sources), /immutable vX\.Y\.Z/);
});

await test("a commit of the default branch is promoted with the digests its build produced", async () => {
	for (const status of ["identical", "ahead"]) {
		const compared: string[] = [];
		assert.deepEqual(
			await resolvePromotion(
				{ ...request, commit, allowRollback: true },
				{
					...sources,
					compare: (base, head) => {
						compared.push(`${base}...${head}`);
						return Promise.resolve(status);
					},
					images: (at) => Promise.resolve(at === commit ? images : {}),
				},
			),
			{
				channel: {
					release: commit,
					images,
					allowRollback: true,
					freeze: false,
					refreshDatabaseImage: false,
				},
				version: commit,
			},
		);
		assert.deepEqual(compared, [`${commit}...main`]);
	}
});

await test("a commit outside the default branch is refused before any image is resolved", async () => {
	for (const status of ["behind", "diverged", "unexpected"])
		await assert.rejects(
			resolvePromotion(
				{ ...request, commit },
				{ ...sources, compare: () => Promise.resolve(status) },
			),
			/not on the default branch/,
		);
	await assert.rejects(
		resolvePromotion(
			{ ...request, commit },
			{ ...sources, compare: () => Promise.reject(new Error("unavailable")) },
		),
		/unavailable/,
	);
});

await test("a promotion names exactly one target, and a commit is named whole", async () => {
	await assert.rejects(
		resolvePromotion({ ...request, release: "v1.2.3", commit }, sources),
		/not both/,
	);
	await assert.rejects(resolvePromotion(request, sources), /Name a release or a commit/);
	for (const short of [commit.slice(0, 7), "main"])
		await assert.rejects(
			resolvePromotion({ ...request, commit: short }, sources),
			/full commit SHA/,
		);
});

await test("only a commit channel can be asked to take the database image it names", async () => {
	assert.deepEqual(
		await resolvePromotion(
			{ ...request, commit, refreshDatabaseImage: true },
			{
				...sources,
				compare: () => Promise.resolve("identical"),
				images: () => Promise.resolve(images),
			},
		),
		{
			channel: {
				release: commit,
				images,
				allowRollback: false,
				freeze: false,
				refreshDatabaseImage: true,
			},
			version: commit,
		},
	);
	// A release runs the images its evidence describes, so it carries nothing to refresh.
	await assert.rejects(
		resolvePromotion(
			{ ...request, release: "v1.2.3", refreshDatabaseImage: true },
			{ ...sources, isDraft: () => Promise.resolve(false) },
		),
		/applies to a commit, not to a release/,
	);
});
