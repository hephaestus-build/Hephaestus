import { assert, describe, expect, it } from "vitest";

import type { Practice, PracticeBinding } from "@/api/types.gen";
import {
	chosenAutonomy,
	inheritedAutonomy,
	mockPractice,
} from "@/components/admin/practices/fixtures";

import {
	applyDisplayOrder,
	applyPracticePlacements,
	placePractice,
	practicePlacementSnapshot,
	selectPracticePatch,
	unassignPractices,
} from "./practice-catalog-cache";

/** A second edit landing on one row, which a structural rollback must leave alone. */
function withAutonomy(practices: Practice[], slug: string, autonomy: Practice["autonomy"]) {
	return practices.map((item) => (item.slug === slug ? { ...item, autonomy } : item));
}

function practice(slug: string, groupSlug: string | undefined, displayOrder: number): Practice {
	return {
		...mockPractice,
		id: displayOrder + 1,
		name: slug,
		slug,
		groupSlug,
		displayOrder,
	};
}

describe("practice catalog cache updates", () => {
	it("reorders only the requested slugs", () => {
		const practices = [
			practice("first", "quality", 0),
			practice("second", "quality", 1),
			practice("elsewhere", "delivery", 0),
		];

		const result = applyDisplayOrder(practices, ["second", "first"]);

		expect(result.map(({ slug, displayOrder }) => [slug, displayOrder])).toStrictEqual([
			["first", 1],
			["second", 0],
			["elsewhere", 0],
		]);
	});

	it("places a practice between rows in another group and compacts both buckets", () => {
		const practices = [
			practice("first", "quality", 0),
			practice("moving", "quality", 1),
			practice("last", "quality", 2),
			practice("before", "delivery", 0),
			practice("after", "delivery", 1),
		];

		const result = placePractice(practices, "moving", "delivery", 1);

		expect(
			result
				.filter(({ groupSlug }) => groupSlug === "quality")
				.sort((a, b) => a.displayOrder - b.displayOrder)
				.map(({ slug, displayOrder }) => [slug, displayOrder]),
		).toStrictEqual([
			["first", 0],
			["last", 1],
		]);
		expect(
			result
				.filter(({ groupSlug }) => groupSlug === "delivery")
				.sort((a, b) => a.displayOrder - b.displayOrder)
				.map(({ slug, displayOrder }) => [slug, displayOrder]),
		).toStrictEqual([
			["before", 0],
			["moving", 1],
			["after", 2],
		]);
	});

	it("unassigns a group's practices without disturbing existing unassigned order", () => {
		const practices = [
			practice("existing", undefined, 0),
			practice("first", "quality", 0),
			practice("second", "quality", 1),
			practice("elsewhere", "delivery", 0),
		];

		const result = unassignPractices(practices, "quality");

		expect(
			result.map(({ slug, groupSlug, displayOrder }) => [slug, groupSlug, displayOrder]),
		).toStrictEqual([
			["existing", undefined, 0],
			["first", undefined, 1],
			["second", undefined, 2],
			["elsewhere", "delivery", 0],
		]);
	});

	it("supports empty destinations and structural-only rollback", () => {
		const practices = [
			{ ...practice("moving", "quality", 0), autonomy: inheritedAutonomy("AUTOMATIC") },
			practice("remaining", "quality", 1),
		];
		const snapshot = practicePlacementSnapshot(practices, "moving", null);
		const moved = withAutonomy(
			placePractice(practices, "moving", null, 0),
			"moving",
			chosenAutonomy("OFF"),
		);

		const restored = applyPracticePlacements(moved, snapshot);

		expect(restored.find(({ slug }) => slug === "moving")).toMatchObject({
			autonomy: chosenAutonomy("OFF"),
			groupSlug: "quality",
			displayOrder: 0,
		});
		expect(restored.find(({ slug }) => slug === "remaining")?.displayOrder).toBe(1);
	});

	it("reconciles only fields owned by the edit request", () => {
		const updated = {
			...practice("edited", "delivery", 4),
			autonomy: chosenAutonomy("OFF"),
			name: "Updated",
		};

		expect(selectPracticePatch(updated, { name: "Updated" })).toStrictEqual({ name: "Updated" });
		expect(
			selectPracticePatch(updated, {
				group: { groupSlug: "delivery" },
			}),
		).toStrictEqual({ groupSlug: "delivery", displayOrder: 4 });
		expect(
			selectPracticePatch({ ...updated, whyItMatters: undefined }, { clear: ["WHY_IT_MATTERS"] }),
		).toStrictEqual({ whyItMatters: undefined });
		expect(
			selectPracticePatch(updated, {
				automatedReviewPolicy: updated.automatedReviewPolicy,
			}),
		).toStrictEqual({
			automatedReviewPolicy: updated.automatedReviewPolicy,
			automatedReviewValidation: updated.automatedReviewValidation,
		});
		// Replacing the occasion can move the practice to a different kind of work, and with it to that
		// kind's recommended review settings — so the optimistic patch carries those too.
		const [replacement] = updated.bindings;
		assert(replacement);
		const occasion: [PracticeBinding] = [replacement];
		expect(selectPracticePatch(updated, { bindings: occasion })).toStrictEqual({
			bindings: occasion,
			artifactKind: updated.artifactKind,
			automatedReviewPolicy: updated.automatedReviewPolicy,
			automatedReviewValidation: updated.automatedReviewValidation,
		});
	});
});
