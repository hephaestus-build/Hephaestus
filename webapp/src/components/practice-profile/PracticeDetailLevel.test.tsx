import { fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import type { ReviewRunFeedState } from "@/components/profile/review-runs";
import {
	detailObservation,
	detailRun,
	detailRuns,
	focusedChanges,
} from "@/stories/practice-detail-story-mock-data";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import { packagingGroup } from "@/stories/practice-profile-story-mock-data";

import type { PracticeTab } from "./practice-profile-search";
import { PracticeDetailLevel, type PracticeDetailLevelProps } from "./PracticeDetailLevel";

const emptyFeed = {
	status: "ready",
	runs: [],
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: vi.fn(),
} satisfies ReviewRunFeedState;

const readyFeed = { ...emptyFeed, runs: [detailRun] } satisfies ReviewRunFeedState;

/** The practice the preview fixtures write feedback about, over the group they belong to. */
const scopedPractice = { ...focusedChanges, slug: "scope-one-reviewable-change" };

/** The level as the drawer mounts it: second in the stack, over its group. */
function levelAt(props: Partial<PracticeDetailLevelProps> = {}) {
	return (
		<DetailDrawerStack
			stack={[
				{ kind: "practice-group", id: packagingGroup.slug },
				{ kind: "practice", id: focusedChanges.slug },
			]}
			onClose={vi.fn()}
		>
			{(entry, level) =>
				entry.kind === "practice" ? (
					<PracticeDetailLevel
						nested={level.nested}
						practice={focusedChanges}
						feedbackCards={ALL_FEEDBACK_CARDS}
						isLoading={false}
						{...props}
					/>
				) : null
			}
		</DetailDrawerStack>
	);
}

function renderLevel(props: Partial<PracticeDetailLevelProps> = {}) {
	return render(levelAt(props));
}

describe("PracticeDetailLevel", () => {
	it("names the practice, where it stands and the path down to the group it is in", async () => {
		const onClose = vi.fn();
		renderLevel({
			feed: readyFeed,
			path: {
				behind: [
					{ label: "Practice profile", depth: 0 },
					{ label: packagingGroup.name, depth: 1 },
				],
				onClose,
			},
		});
		await screen.findByRole("heading", { name: focusedChanges.name });
		expect(screen.getByRole("list", { name: "Path" }).textContent).toBe(
			`Practice profile${packagingGroup.name}Practice`,
		);
		screen.getByText("Mixed feedback");
		screen.getByText("More positive recently");
		fireEvent.click(screen.getByRole("button", { name: packagingGroup.name }));
		expect(onClose).toHaveBeenCalledWith(1);
	});

	it("opens on the observations, counted, and shows only this practice's, the newest open", async () => {
		renderLevel({ feed: readyFeed });
		const observations = await screen.findByRole("tab", { name: "Observations 1" });
		expect(observations.getAttribute("aria-selected")).toBe("true");
		screen.getByRole("heading", { name: "Observations" });
		screen.getByText(detailObservation.summary);
		screen.getByText("Needs improvement");
		// The run also observed another practice; only this practice's observation is shown.
		expect(screen.queryByText("The description names the motivation")).toBeNull();
		// Open on arrival, with everything the feed carries; nothing was loaded to open it.
		screen.getByText("Why it was noted");
		screen.getByText(/renames the loader's package and changes its caching/u);
		screen.getByText("Next step");
		// The rows are this practice's own, so none repeats its name under the summary.
		expect(screen.getAllByText(focusedChanges.name)).toHaveLength(1);
	});

	it("opens the newest observation alone and leaves the earlier ones to a press", async () => {
		renderLevel({ feed: { ...emptyFeed, runs: detailRuns } });
		const newest = await screen.findByRole("button", {
			name: new RegExp(detailObservation.summary, "u"),
		});
		const earlier = screen.getByRole("button", {
			name: /A dependency bump was carried alongside a behaviour change/u,
		});
		expect(newest.getAttribute("aria-expanded")).toBe("true");
		expect(earlier.getAttribute("aria-expanded")).toBe("false");
		expect(screen.getAllByText("Why it was noted")).toHaveLength(1);

		fireEvent.click(earlier);
		expect(earlier.getAttribute("aria-expanded")).toBe("true");
		expect(newest.getAttribute("aria-expanded")).toBe("true");
	});

	it("switches tabs through the route", async () => {
		// The tab is the route's: the level asks for it and shows whatever comes back.
		function RoutedTabs() {
			const [tab, setTab] = useState<PracticeTab>();
			return levelAt({ feed: readyFeed, tab, onTabChange: setTab });
		}
		render(<RoutedTabs />);
		const about = await screen.findByRole("tab", { name: "About this practice" });
		expect(screen.queryByText("Why it matters")).toBeNull();
		fireEvent.click(about);

		expect(about.getAttribute("aria-selected")).toBe("true");
		await screen.findByText("Why it matters");
	});

	it("explains the practice on its own tab", async () => {
		renderLevel({ tab: "about" });
		await screen.findByText("Why it matters");
		screen.getByText(/A change that does one thing is faster to understand/u);
		screen.getByText("What good looks like");
		expect(screen.queryByText("Next step")).toBeNull();
	});

	it("says when nothing is written about the practice, under its own heading", async () => {
		renderLevel({
			tab: "about",
			practice: { ...focusedChanges, whyItMatters: undefined, whatGoodLooksLike: undefined },
		});
		await screen.findByText("No description yet.");
		expect(screen.getByRole("region", { name: "About this practice" }).textContent).toContain(
			"No description yet.",
		);
		expect(screen.queryByText("Why it matters")).toBeNull();
	});

	it("says where the reader stands in the registry's words and what each rests on", async () => {
		renderLevel({ tab: "about" });
		await screen.findByText("Where you stand");
		const section = screen.getByRole("region", { name: "Where you stand" });
		within(section).getByText(
			"Recent reviews found both strengths and problems here. Based on your latest six pieces of reviewed work.",
		);
		within(section).getByText(
			"Recent reviewed work carried more strengths than the stretch before it. Compared your latest six pieces of reviewed work with the five before them. Evidence spans 12 days.",
		);
		// The sentences are printed beside the badge and the chip, so neither is a tooltip's trigger.
		within(section).getByText("Mixed feedback");
		within(section).getByText("More positive recently");
		expect(within(section).queryByRole("button")).toBeNull();
		// Both lines sit in one bordered box under the label.
		const box = within(section).getByText(/^Recent reviews found/u).parentElement;
		expect(box?.classList.contains("border")).toBe(true);
		expect(within(section).getByText(/^Recent reviewed work carried/u).parentElement).toBe(box);
	});

	it("claims no basis and no direction for a practice no review has settled", async () => {
		renderLevel({
			tab: "about",
			practice: {
				...focusedChanges,
				standing: "NOT_OBSERVED",
				direction: undefined,
				trendSupport: undefined,
			},
		});
		await screen.findByText("This practice has no current verdict for you yet.");
		expect(screen.queryByText(/Based on your latest/u)).toBeNull();
		// The header's chip is the only one.
		expect(screen.getAllByText("Not enough to compare yet")).toHaveLength(1);
	});

	it("lists the practice's feedback under a heading of the Observations tab's rank", async () => {
		renderLevel({ tab: "feedback", practice: scopedPractice });
		await screen.findByRole("tab", { name: "Feedback 1" });
		screen.getByRole("heading", { level: 2, name: "Feedback" });
		screen.getByRole("heading", { level: 3, name: "Current feedback" });
		screen.getByRole("heading", { name: "Merge requests bundle a fix with a refactor" });
		// Nothing resolved, so no label claims a list of it.
		expect(screen.queryByText("Resolved feedback")).toBeNull();
	});

	it("opens a practice a card names only when told how", async () => {
		const onOpenPractice = vi.fn();
		const { rerender } = renderLevel({ tab: "feedback", practice: scopedPractice });
		await screen.findByText("Current feedback");
		expect(screen.queryByRole("button", { name: "Scope the change to one concern" })).toBeNull();

		rerender(levelAt({ tab: "feedback", practice: scopedPractice, onOpenPractice }));
		fireEvent.click(await screen.findByRole("button", { name: "Scope the change to one concern" }));
		expect(onOpenPractice).toHaveBeenCalledExactlyOnceWith("scope-one-reviewable-change");
	});

	it("says when no feedback was written about the practice, as the Observations tab does", async () => {
		renderLevel({ tab: "feedback" });
		await screen.findByText("No feedback yet.");
		screen.getByText("Feedback appears once the same shortcoming keeps showing up on your work.");
		expect(screen.queryByText("Current feedback")).toBeNull();
		expect(screen.queryByText("Resolved feedback")).toBeNull();
	});

	it("draws the tabs and the feed in their own shape while the level loads", async () => {
		renderLevel({ isLoading: true, practice: undefined, skeletonRows: 2 });
		await screen.findByText("Loading review runs");
		expect(screen.queryByRole("tab")).toBeNull();
	});

	it("tells a practice no review reached apart from a feed still loading", async () => {
		const { rerender } = renderLevel({ feed: { status: "loading" } });
		await screen.findByText("Loading review runs");
		screen.getByRole("tab", { name: "Observations" });

		rerender(levelAt({ feed: emptyFeed }));
		await screen.findByText("No review has reached this practice yet.");
	});

	it("loads earlier reviews without losing the ones already shown", async () => {
		const onLoadMore = vi.fn();
		renderLevel({ feed: { ...readyFeed, hasMore: true, onLoadMore } });
		fireEvent.click(await screen.findByRole("button", { name: "View earlier reviews" }));
		expect(onLoadMore).toHaveBeenCalledOnce();
		screen.getByText(detailObservation.summary);
	});

	it("closes an observation on a press, on its own, without writing anything", async () => {
		renderLevel({ feed: readyFeed });
		const row = await screen.findByRole("button", {
			name: new RegExp(detailObservation.summary, "u"),
		});
		expect(row.getAttribute("aria-expanded")).toBe("true");
		fireEvent.click(row);
		expect(row.getAttribute("aria-expanded")).toBe("false");
		expect(screen.queryByText("Why it was noted")).toBeNull();
	});

	it("shows the response controls with what the reader already said", async () => {
		renderLevel({
			feed: {
				...readyFeed,
				runs: [
					{
						...detailRun,
						observations: [{ ...detailObservation, feedbackResolution: "ADDRESSED" }],
					},
				],
			},
			observations: { onRespond: vi.fn() },
		});
		await screen.findByText("Your response");
		expect(screen.getByRole("button", { name: "Addressed" }).getAttribute("aria-pressed")).toBe(
			"true",
		);
		screen.getByRole("button", { name: "Disputed" });
	});

	it("offers a retry when the feed itself failed", async () => {
		const onRetry = vi.fn();
		renderLevel({ feed: { status: "error", error: new Error("Gateway timeout"), onRetry } });
		fireEvent.click(await screen.findByRole("button", { name: /retry/iu }));
		expect(onRetry).toHaveBeenCalledOnce();
	});

	it("says when the practice is not reviewed here", async () => {
		renderLevel({ practice: undefined });
		await screen.findByText("This practice does not exist or is not reviewed in this group.");
		expect(screen.queryByRole("tab")).toBeNull();
	});
});
