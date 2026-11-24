import { describe, expect, it } from "vitest";

import type { Practice } from "@/api/types.gen";
import {
	chosenFeedbackMode,
	inheritedFeedbackMode,
	mockGroups,
	mockPractice,
} from "@/components/admin/practices/fixtures";

import {
	applyPositions,
	applyPracticePositions,
	movePractice,
	practicePositionSnapshot,
	unassignPractices,
	withGroupDetails,
} from "./practice-catalog-cache";

/** A second edit landing on one row, which a structural rollback must leave alone. */
function withFeedbackMode(
	practices: Practice[],
	slug: string,
	feedbackMode: Practice["feedbackMode"],
) {
	return practices.map((item) => (item.slug === slug ? { ...item, feedbackMode } : item));
}

function practice(slug: string, groupSlug: string | null, position: number): Practice {
	return { ...mockPractice, id: String(position + 1), slug, groupSlug, position };
}

const positions = (practices: Practice[], groupSlug: string) =>
	practices
		.filter((candidate) => candidate.groupSlug === groupSlug)
		.sort((a, b) => a.position - b.position)
		.map(({ slug, position }) => [slug, position]);

describe("practice catalog cache updates", () => {
	it("reorders only the requested slugs", () => {
		const practices = [
			practice("first", "quality", 0),
			practice("second", "quality", 1),
			practice("elsewhere", "delivery", 0),
		];

		const result = applyPositions(practices, ["second", "first"]);

		expect(result.map(({ slug, position }) => [slug, position])).toStrictEqual([
			["first", 1],
			["second", 0],
			["elsewhere", 0],
		]);
	});

	it("places a practice between rows in another group and compacts both groups", () => {
		const practices = [
			practice("first", "quality", 0),
			practice("moving", "quality", 1),
			practice("last", "quality", 2),
			practice("before", "delivery", 0),
			practice("after", "delivery", 1),
		];

		const result = movePractice(practices, "moving", "delivery", 1);

		expect(positions(result, "quality")).toStrictEqual([
			["first", 0],
			["last", 1],
		]);
		expect(positions(result, "delivery")).toStrictEqual([
			["before", 0],
			["moving", 1],
			["after", 2],
		]);
	});

	it("unassigns a group's practices without disturbing existing unassigned order", () => {
		const practices = [
			practice("existing", null, 0),
			practice("first", "quality", 0),
			practice("second", "quality", 1),
			practice("elsewhere", "delivery", 0),
		];

		const result = unassignPractices(practices, "quality");

		expect(
			result.map(({ slug, groupSlug, position }) => [slug, groupSlug, position]),
		).toStrictEqual([
			["existing", null, 0],
			["first", null, 1],
			["second", null, 2],
			["elsewhere", "delivery", 0],
		]);
	});

	it("supports empty destinations and structural-only rollback", () => {
		const practices = [
			{ ...practice("moving", "quality", 0), feedbackMode: inheritedFeedbackMode("AUTOMATIC") },
			practice("remaining", "quality", 1),
		];
		const snapshot = practicePositionSnapshot(practices, "moving", null);
		const moved = withFeedbackMode(
			movePractice(practices, "moving", null, 0),
			"moving",
			chosenFeedbackMode("OFF"),
		);

		const restored = applyPracticePositions(moved, snapshot);

		expect(restored.find(({ slug }) => slug === "moving")).toMatchObject({
			feedbackMode: chosenFeedbackMode("OFF"),
			groupSlug: "quality",
			position: 0,
		});
		expect(restored.find(({ slug }) => slug === "remaining")?.position).toBe(1);
	});

	it("replaces a group's details and keeps its place and feedback mode", () => {
		const [group] = withGroupDetails(mockGroups, "review-ready-work", {
			name: "Code quality",
			description: null,
			icon: "Code",
			color: null,
			shownToPeople: false,
		});

		expect(group).toMatchObject({
			name: "Code quality",
			icon: "Code",
			shownToPeople: false,
			position: mockGroups[0]?.position,
			feedbackMode: mockGroups[0]?.feedbackMode,
		});
	});
});
