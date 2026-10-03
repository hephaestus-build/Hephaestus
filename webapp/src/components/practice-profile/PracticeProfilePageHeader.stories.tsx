import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ARTIFACT_KIND } from "@/lib/artifact-kinds";
import { expectNoPageOverflow, expectTargetSize } from "@/stories/reflow";
import { inStoryYear } from "@/stories/story-clock";

import { PracticeProfilePageHeader } from "./PracticeProfilePageHeader";

const meta = {
	component: PracticeProfilePageHeader,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		latestRun: {
			reviewId: "run-2026-09-09",
			at: inStoryYear("09-09T14:10:00"),
			reviewedWork: {
				id: "C01/p1",
				label: "#releases",
				url: "https://example.slack.com/archives/C01/p1",
				kind: ARTIFACT_KIND.conversationThread,
			},
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
		const items = canvas.getAllByRole("listitem");
		await expect(items.map((item) => item.textContent)).toEqual([
			"2Needs attention",
			"3Mixed",
			"7Going well",
			"1Nothing to report",
			"3Not observed",
		]);

		// The chip is one control, and the level it opens is an address.
		const chip = canvas.getByRole("link", { name: /^Latest review/u });
		await expectTargetSize(chip);
		await expect(chip).toHaveAttribute("href", expect.stringContaining("reviews%3Aall"));

		// One destination, and the card is all of it: the words are the keyboard path and their
		// pseudo-element covers the card for the pointer. The level it opens is an address.
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toHaveAttribute(
			"href",
			expect.stringContaining("practice-groups%3Aall"),
		);
	},
};

/** A standing at zero drops out of the legend rather than reading "0 Needs attention". */
export const SomeStandingsAbsent: Story = {
	args: {
		counts: { STRENGTH: 9, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 7 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem")).toHaveLength(2);
		await expect(canvas.queryByText("Needs attention")).toBeNull();
	},
};

/**
 * Nothing reviewed yet: every practice stands at "Not observed", so the ring is one grey arc and
 * the legend one line, and the card still names its destination. With no review there is nothing
 * to open, so the chip is a plain badge rather than a door onto an empty list.
 */
export const NothingObservedYet: Story = {
	args: {
		latestRun: undefined,
		counts: { STRENGTH: 0, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 16 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No review yet")).toBeVisible();
		await expect(canvas.queryByRole("link", { name: /review/iu })).toBeNull();
		await expect(canvas.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
			"16Not observed",
		]);
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
		latestRun: {
			reviewId: "run-2026-09-09",
			at: inStoryYear("09-09T14:10:00"),
			reviewedWork: {
				id: "queue-retry-policy",
				label: "Queue retry policy for the notification pipeline",
				url: "https://outline.example.com/doc/queue-retry-policy",
				kind: ARTIFACT_KIND.document,
			},
		},
	},
	play: async ({ canvas }) => {
		const cut = canvas.getByText(/^after Queue retry policy/u);
		await expect(cut.scrollWidth).toBeGreaterThan(cut.clientWidth);
		await expectNoPageOverflow();
	},
};

/** A review is being run now, and the chip says so in words. */
export const ReviewRunning: Story = {
	args: {
		latestRun: {
			reviewId: "run-2026-09-27",
			at: inStoryYear("09-27T09:20:00"),
			reviewedWork: { id: "905", label: "#905", kind: ARTIFACT_KIND.pullRequest },
			status: "IN_PROGRESS",
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
		latestRun: {
			reviewId: "run-2026-09-25",
			at: inStoryYear("09-25T11:13:00"),
			reviewedWork: { id: "890", label: "#890", kind: ARTIFACT_KIND.pullRequest },
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
		latestRun: undefined,
		counts: { STRENGTH: 0, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 0 },
		practiceCount: 0,
		groupCount: 0,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No review yet")).toBeVisible();
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
		await expect(canvas.queryByRole("list")).toBeNull();
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
	},
};

/**
 * The title, the introduction and the destination stand while the counts load; only the chip and
 * the counts wait, and no count is shown as zero in the meantime.
 */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("Practice profile");
		await expect(canvas.queryByText("16 practices in 5 groups")).toBeNull();
		await expect(canvas.queryByRole("list")).toBeNull();
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
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
