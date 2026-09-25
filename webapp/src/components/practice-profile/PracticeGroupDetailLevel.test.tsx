import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";

import type { DetailStackEntry } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { detailPractices, focusedChanges } from "@/stories/practice-detail-story-mock-data";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	OVERVIEW_FIXTURE,
	packagingGroup,
	packagingStanding,
} from "@/stories/practice-profile-story-mock-data";

import { composeNextStep, composeOverview, groupOverviewOf } from "./compose-overview";
import { practiceLevel, type PracticeProfileDetailLevelKind } from "./practice-profile-search";
import { PracticeDetailLevel } from "./PracticeDetailLevel";
import { PracticeGroupDetailLevel } from "./PracticeGroupDetailLevel";

/** What the route composes for the group's level: its slice of the overview and its next step. */
const groupOverview = (groupSlug: string) => ({
	...groupOverviewOf(composeOverview(OVERVIEW_FIXTURE), groupSlug),
	nextStep: composeNextStep(ALL_FEEDBACK_CARDS, groupSlug),
});

/**
 * The group level and, over it, the practice it opens — wired the way the profile's drawer wires
 * them, so a press on a practice row is followed to the level it produces. The level's own
 * rendering is proven by its stories; this is the one thing they cannot mount.
 */
function GroupWithPractices() {
	const [stack, setStack] = useState<DetailStackEntry<PracticeProfileDetailLevelKind>[]>([
		{ kind: "practice-group", id: packagingGroup.slug },
	]);
	const close = (depth: number) => setStack(stack.slice(0, depth));
	return (
		<DetailDrawerStack stack={stack} onClose={close}>
			{(entry, level) =>
				entry.kind === "practice" ? (
					<PracticeDetailLevel
						nested={level.nested}
						path={{
							behind: [
								{ label: "Profile", depth: 0 },
								{ label: packagingGroup.name, depth: 1 },
							],
							onClose: close,
						}}
						practice={detailPractices.find((practice) => practice.slug === entry.id)}
						isLoading={false}
					/>
				) : (
					<PracticeGroupDetailLevel
						nested={level.nested}
						group={packagingGroup}
						standing={packagingStanding}
						practices={detailPractices}
						{...groupOverview(packagingGroup.slug)}
						onOpenPractice={(slug) => setStack([...stack, practiceLevel(slug)])}
						isLoading={false}
					/>
				)
			}
		</DetailDrawerStack>
	);
}

describe("PracticeGroupDetailLevel", () => {
	it("opens a practice as its own level over the group", async () => {
		render(<GroupWithPractices />);
		fireEvent.click(await screen.findByRole("button", { name: `Open ${focusedChanges.name}` }));

		// The practice pressed is the one that opens, and its path leads back through this group.
		await screen.findByRole("heading", { name: focusedChanges.name });
		expect(screen.getByRole("list", { name: "Path" }).textContent).toBe(
			`Profile${packagingGroup.name}Practice`,
		);
	});
});
