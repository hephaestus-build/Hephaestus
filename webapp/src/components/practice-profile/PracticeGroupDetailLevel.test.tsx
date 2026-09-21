import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import type { DetailStackEntry } from "@/components/core/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/core/detail-drawer/DetailDrawerStack";
import { detailPractices } from "@/stories/practice-detail-story-mock-data";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	OVERVIEW_FIXTURE,
	packagingGroup,
	packagingStanding,
} from "@/stories/practice-profile-story-mock-data";

import { composeNextStep, composeOverview, groupOverviewOf } from "./compose-overview";
import { practiceLevel, type PracticeProfileDetailLevelKind } from "./practice-profile-search";
import { PracticeDetailLevel } from "./PracticeDetailLevel";
import {
	PracticeGroupDetailLevel,
	type PracticeGroupDetailLevelProps,
} from "./PracticeGroupDetailLevel";

/** What the route composes for the group's level: its slice of the overview and its next step. */
const groupOverview = (groupSlug: string) => ({
	...groupOverviewOf(composeOverview(OVERVIEW_FIXTURE), groupSlug),
	nextStep: composeNextStep(ALL_FEEDBACK_CARDS, groupSlug),
});

function renderLevel(props: Partial<PracticeGroupDetailLevelProps> = {}) {
	return render(
		<DetailDrawerStack
			stack={[{ kind: "practice-group", id: packagingGroup.slug }]}
			onClose={vi.fn()}
		>
			{() => (
				<PracticeGroupDetailLevel
					group={packagingGroup}
					standing={packagingStanding}
					practices={detailPractices}
					{...groupOverview(props.group?.slug ?? packagingGroup.slug)}
					isLoading={false}
					{...props}
				/>
			)}
		</DetailDrawerStack>,
	);
}

/**
 * The group level and, over it, the practice it opens — wired the way the profile's drawer wires
 * them, so a press on a practice row is followed to the level it produces.
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

const practiceRows = () =>
	within(screen.getByRole("table", { name: "Practices in this group" }))
		.getAllByRole("row")
		.slice(1);

describe("PracticeGroupDetailLevel", () => {
	it("says where the reader stands in the group, beside its title", async () => {
		renderLevel();
		await screen.findByRole("heading", { name: packagingGroup.name });
		screen.getByText("Group");
		// A line between the header and Heph's card, so the two do not touch.
		expect(
			document.querySelector("[data-slot='drawer-body'] > [data-slot='separator']"),
		).not.toBeNull();
		// The practice count and its ring are the header's second column, beside the title.
		const summary = screen.getByText("three practices in this group");
		expect(summary.closest("[data-slot='drawer-header']")).not.toBeNull();
		expect(screen.queryByText("Suggested next step")).toBeNull();
	});

	it("opens on the practices, counted, with the group's words on the other tab", async () => {
		renderLevel();
		const practices = await screen.findByRole("tab", { name: "Practices 3" });
		expect(practices.getAttribute("aria-selected")).toBe("true");
		const about = screen.getByRole("tab", { name: "About this group" });
		expect(screen.queryByText(/one concern per change/)).toBeNull();
		fireEvent.click(about);

		expect(about.getAttribute("aria-selected")).toBe("true");
		// The description has a heading over it, like every passage on the level.
		await screen.findByText(/one concern per change/);
		expect(screen.getByRole("region", { name: "About this group" }).textContent).toContain(
			"one concern per change",
		);
		await waitFor(() =>
			expect(screen.queryByRole("table", { name: "Practices in this group" })).toBeNull(),
		);
		// Heph's card is the group's summary, over the tabs rather than in one.
		screen.getByText("What is holding up well");
	});

	it("says where the reader stands in the group and what it rests on, on the About tab", async () => {
		renderLevel();
		fireEvent.click(await screen.findByRole("tab", { name: "About this group" }));
		const stand = await screen.findByRole("region", { name: "Where you stand" });
		within(stand).getByText(
			"Recent reviews here were mostly problems. Of three practices, one shows mixed feedback, one is going well and one is not observed yet.",
		);
		within(stand).getByText(/^Recent reviewed work carried more problems/);
		within(stand).getByText("Needs attention");
		within(stand).getByText("More difficulties recently");
	});

	it("claims no basis and no direction for a group no review has settled", async () => {
		renderLevel({
			standing: {
				...packagingStanding,
				standing: "NOT_OBSERVED",
				direction: undefined,
				trendSupport: undefined,
			},
			practices: [],
		});
		fireEvent.click(await screen.findByRole("tab", { name: "About this group" }));
		const stand = await screen.findByRole("region", { name: "Where you stand" });
		within(stand).getByText("No practice in this group has a current verdict for you.");
		expect(within(stand).queryByText(/^Of /)).toBeNull();
		expect(within(stand).queryByText("Not enough to compare yet")).toBeNull();
	});

	it("says when nothing is written about the group, under the same heading", async () => {
		renderLevel({ group: { ...packagingGroup, description: undefined } });
		fireEvent.click(await screen.findByRole("tab", { name: "About this group" }));
		await screen.findByText("No description yet.");
		expect(screen.getByRole("region", { name: "About this group" }).textContent).toContain(
			"No description yet.",
		);
	});

	it("says what holds and the next step in this group", async () => {
		renderLevel();
		await screen.findByText("What is holding up well");
		screen.getByText("Next step");
		screen.getByText(/That is the open next step on/);
	});

	it("lists every practice with its standing, sorted needs attention first", async () => {
		renderLevel();
		await screen.findByText("Practices in this group");
		const rows = practiceRows();
		expect(rows).toHaveLength(3);
		expect(rows[0]?.textContent).toContain("Keep changes focused");
		expect(rows[0]?.textContent).toContain("Mixed feedback");
		expect(rows[2]?.textContent).toContain("Link the issue the change resolves");
		// A practice with a sentence carries it under its pill; the others show only the pill.
		screen.getByText(/^Feedback resolved by the work after/);
	});

	it("marks the row of the practice whose level is open over this one", async () => {
		renderLevel({ openPracticeSlug: "small-changes" });
		await screen.findByText("Practices in this group");
		const openRows = practiceRows().filter((row) => row.getAttribute("data-state") === "open");
		expect(openRows).toHaveLength(1);
		expect(openRows[0]?.textContent).toContain("Keep changes focused");
	});

	it("reverses the order when the Standing header is pressed again", async () => {
		renderLevel();
		const standing = await screen.findByRole("button", { name: "Standing" });
		fireEvent.click(standing);
		expect(standing.closest("th")?.getAttribute("aria-sort")).toBe("descending");
		expect(practiceRows()[0]?.textContent).toContain("Link the issue the change resolves");
	});

	it("opens a practice as its own level over the group", async () => {
		render(<GroupWithPractices />);
		fireEvent.click(await screen.findByRole("button", { name: "Open Keep changes focused" }));

		// The practice pressed is the one that opens, and its path leads back through this group.
		await screen.findByRole("heading", { name: "Keep changes focused" });
		expect(screen.getByRole("list", { name: "Path" }).textContent).toBe(
			`Profile${packagingGroup.name}Practice`,
		);
	});

	it("leaves out what Heph has nothing to say about when the group reviews no practice yet", async () => {
		// A group the overview does not mention, so nothing stands in for the missing practices.
		renderLevel({ group: { ...packagingGroup, slug: "testing-discipline" }, practices: [] });
		await screen.findByText("No practices here yet.");
		expect(screen.queryByText("What is holding up well")).toBeNull();
		expect(screen.queryByText("Next step")).toBeNull();
		// The reviewed work is still there to show, so the card stays for its footer.
		screen.getByText("pull requests");
	});

	it("offers a retry when the standing failed to load", async () => {
		const onRetry = vi.fn();
		renderLevel({ error: new Error("Unavailable"), onRetry });
		fireEvent.click(await screen.findByRole("button", { name: /retry/i }));
		expect(onRetry).toHaveBeenCalledOnce();
	});
});
