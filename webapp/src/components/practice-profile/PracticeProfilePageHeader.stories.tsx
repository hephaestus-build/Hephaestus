import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { forgetSectionFold } from "@/components/layout/section-folds";
import { conversation, document, pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow, expectTargetSize } from "@/stories/reflow";
import { inStoryYear } from "@/stories/story-clock";

import { GUIDE_FOLD_KEY, PracticeProfilePageHeader } from "./PracticeProfilePageHeader";

/** The ring's legend, apart from the list of points in "How to read this page". */
const LEGEND = "Practices by standing";

const meta = {
	component: PracticeProfilePageHeader,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	// Each story starts from a first visit, so none inherits a guide another story closed.
	beforeEach: () => {
		forgetSectionFold(GUIDE_FOLD_KEY);
		return () => forgetSectionFold(GUIDE_FOLD_KEY);
	},
	args: {
		latestReview: {
			reviewId: "5d1e2a3b-0000-4000-8000-000000000909",
			at: inStoryYear("09-09T14:10"),
			item: conversation("#releases"),
			status: "COMPLETE",
		},
		counts: { STRENGTH: 7, MIXED: 3, DEVELOPING: 2, NO_OPPORTUNITY: 1, NOT_OBSERVED: 3 },
		practiceCount: 16,
		groupCount: 5,
		isLoading: false,
	},
} satisfies Meta<typeof PracticeProfilePageHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("Practice profile");
		await expect(canvas.getByText("9 September, 2:10 pm")).toBeVisible();
		await expect(canvas.getByText("16 practices in 5 groups")).toBeVisible();

		// Every standing with a count is listed, in the registry's order: what needs attention first,
		// the same order the ring draws its arcs and the tables sort by.
		const items = within(canvas.getByRole("list", { name: LEGEND })).getAllByRole("listitem");
		await expect(items.map((item) => item.textContent)).toEqual([
			"2Needs attention",
			"3Mixed",
			"7Going well",
			// "Nothing to report yet" and "Not reviewed" count as one.
			"4No standing yet",
		]);

		// The chip is one control, and the level it opens is an address.
		const chip = canvas.getByRole("link", { name: /^Latest review/u });
		await expectTargetSize(chip);
		await expect(chip).toHaveAttribute("href", expect.stringContaining("reviews:all"));

		// One destination, and the card is all of it: the words are the keyboard path and their
		// pseudo-element covers the card for the pointer. The level it opens is an address.
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toHaveAttribute(
			"href",
			expect.stringContaining("practice-groups:all"),
		);
	},
};

/** A standing at zero drops out of the legend rather than reading "0 Needs attention". */
export const SomeStandingsAbsent: Story = {
	args: {
		counts: { STRENGTH: 9, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 7 },
	},
	play: async ({ canvas }) => {
		const legend = canvas.getByRole("list", { name: LEGEND });
		await expect(within(legend).getAllByRole("listitem")).toHaveLength(2);
		await expect(within(legend).queryByText("Needs attention")).toBeNull();
	},
};

export const NothingReviewedYet: Story = {
	args: {
		latestReview: undefined,
		counts: { STRENGTH: 0, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 16 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No review yet")).toBeVisible();
		await expect(canvas.queryByRole("link", { name: /review/iu })).toBeNull();
		await expect(
			within(canvas.getByRole("list", { name: LEGEND }))
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		).toEqual(["16No standing yet"]);
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
	},
};

/**
 * A work label longer than the chip is cut at the chip's edge rather than widening the page; the
 * link still carries all of it.
 */
export const LongWorkLabel: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	args: {
		latestReview: {
			reviewId: "5d1e2a3b-0000-4000-8000-000000000909",
			at: inStoryYear("09-09T14:10"),
			item: document(1, "Queue retry policy for the notification pipeline"),
			status: "COMPLETE",
		},
	},
	play: async ({ canvas }) => {
		const cut = canvas.getByText(/^after Queue retry policy/u);
		await expect(cut.scrollWidth).toBeGreaterThan(cut.clientWidth);
		await expectNoPageOverflow();
	},
};

/** A review is being run now, and the chip says so in words. */
export const ReviewInProgress: Story = {
	args: {
		latestReview: {
			reviewId: "5d1e2a3b-0000-4000-8000-000000000927",
			at: inStoryYear("09-27T09:20"),
			item: pullRequest(905),
			status: "RUNNING",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /^Review running/u })).toBeVisible();
		await expect(canvas.getByText("on #905")).toBeVisible();
	},
};

/** A review that stopped before it finished says so about the work, not about the machinery. */
export const LatestReviewFailed: Story = {
	args: {
		latestReview: {
			reviewId: "5d1e2a3b-0000-4000-8000-000000000925",
			at: inStoryYear("09-25T11:13"),
			item: pullRequest(890),
			status: "FAILED",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("stopped before it finished")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /^Latest review/u })).toBeVisible();
	},
};

/** A workspace with no practices: the chip says no review has run, and the card has nothing to count. */
export const Empty: Story = {
	args: {
		latestReview: undefined,
		counts: { STRENGTH: 0, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 0 },
		practiceCount: 0,
		groupCount: 0,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No review yet")).toBeVisible();
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
		await expect(canvas.queryByRole("list", { name: LEGEND })).toBeNull();
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
	},
};

/**
 * The title, the introduction, "How to read this page" and the destination stand while the counts
 * load; only the chip and the counts wait, and no count is shown as zero in the meantime.
 */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("Practice profile");
		await expect(canvas.getByRole("button", { name: "How to read this page" })).toBeVisible();
		await expect(canvas.queryByText("16 practices in 5 groups")).toBeNull();
		await expect(canvas.queryByRole("list", { name: LEGEND })).toBeNull();
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
	},
};

/** "How to read this page" opens on a first visit, and the reader can close it. */
export const GuideOpensFirstAndCloses: Story = {
	play: async ({ canvas, userEvent }) => {
		const toggle = canvas.getByRole("button", { name: "How to read this page" });
		await expect(toggle).toHaveAttribute("aria-expanded", "true");
		await userEvent.click(toggle);
		await expect(toggle).toHaveAttribute("aria-expanded", "false");
	},
};

/**
 * The five standings at 320 px: the legend wraps and the destination takes the row under it,
 * without widening the page.
 */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
