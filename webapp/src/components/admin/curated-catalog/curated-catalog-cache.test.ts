import { assert, describe, expect, it } from "vitest";

import type { CatalogEntryStatus, CuratedCatalog } from "@/api/types.gen";

import {
	orderedPracticeSlugs,
	placeCuratedPractice,
	reorderCuratedGroups,
	reorderCuratedPractices,
} from "./curated-catalog-cache";

const status: CatalogEntryStatus = {
	etag: "entry",
	state: "FROM_HEPHAESTUS",
	changeKind: "NONE",
	offered: true,
};

const automatedReview = {
	mode: "LANGUAGE_MODEL",
	evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
} as const;

const catalog = {
	etag: "structure",
	customOrder: false,
	summary: {
		total: 5,
		updatesChangingReview: 0,
		updatesChangingWordingOnly: 0,
		updatesChangingPresentation: 0,
		editedHere: 0,
		yours: 0,
		notOffered: 0,
		noLongerShipped: 0,
	},
	groups: [
		{ slug: "a", position: 0, definition: { name: "A" }, status },
		{ slug: "b", position: 1, definition: { name: "B" }, status: { ...status, offered: false } },
	],
	practices: [
		{
			slug: "one",
			name: "One",
			artifactKind: "scm.issue",
			automatedReview,
			groupSlug: "a",
			position: 0,
			effectivelyOffered: true,
			status,
		},
		{
			slug: "two",
			name: "Two",
			artifactKind: "scm.issue",
			automatedReview,
			groupSlug: "a",
			position: 1,
			effectivelyOffered: true,
			status,
		},
		{
			slug: "three",
			name: "Three",
			artifactKind: "scm.issue",
			automatedReview,
			groupSlug: "b",
			position: 0,
			effectivelyOffered: false,
			status,
		},
	],
} satisfies CuratedCatalog;

describe("curated catalog cache", () => {
	it("reorders groups without changing their definitions", () => {
		const updated = reorderCuratedGroups(catalog, ["b", "a"]);
		expect(updated.groups.map(({ slug, position }) => ({ slug, position }))).toStrictEqual([
			{ slug: "a", position: 1 },
			{ slug: "b", position: 0 },
		]);
		const [reordered] = updated.groups;
		const [original] = catalog.groups;
		assert(reordered);
		assert(original);
		expect(reordered.definition).toBe(original.definition);
	});

	it("moves a practice and normalizes both buckets", () => {
		const updated = placeCuratedPractice(catalog, "two", "b", 0);
		expect(orderedPracticeSlugs(updated, "a")).toStrictEqual(["one"]);
		expect(orderedPracticeSlugs(updated, "b")).toStrictEqual(["two", "three"]);
		expect(updated.practices.find(({ slug }) => slug === "two")?.effectivelyOffered).toBe(false);
	});

	it("reorders within one group", () => {
		const updated = reorderCuratedPractices(catalog, "a", ["two", "one"]);
		expect(orderedPracticeSlugs(updated, "a")).toStrictEqual(["two", "one"]);
	});
});
