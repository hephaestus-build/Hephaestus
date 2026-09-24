import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import type { PracticeGroupReviewRun } from "@/api/types.gen";
import { detailRun } from "@/stories/practice-detail-story-mock-data";
import { daysBefore } from "@/stories/story-clock";

import { ReviewRunTimeline } from "./ReviewRunTimeline";

/** Two runs on different kinds of work, each with what the feed carries about its observations. */
const runs: PracticeGroupReviewRun[] = [
	detailRun,
	{
		reviewId: "00000000-0000-0000-0000-000000000201",
		reviewedAt: daysBefore(5),
		reviewedWork: {
			kind: "chat.conversation_thread",
			id: "42",
			label: "#dev-hephaestus",
		},
		observations: [
			{
				id: "00000000-0000-0000-0000-000000000202",
				practiceSlug: "asks-answerable-questions",
				practiceName: "Ask questions a teammate can answer",
				summary: "The question includes the attempted fix",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "GOOD",
				observedAt: daysBefore(5),
				origin: "LIVE",
				claimCurrentness: "CURRENT",
				artifactId: 42,
				artifactKind: "chat.conversation_thread",
				evidenceRationale:
					"The message names what was tried and where it stopped, so an answer can start there.",
			},
		],
	},
];

const meta = {
	component: ReviewRunTimeline,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { runs, observations: { onRespond: fn() } },
} satisfies Meta<typeof ReviewRunTimeline>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A group's feed: every observation open on arrival with what the feed carries, each row naming
 * its practice, and each closing on its own without disturbing the others.
 */
export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		const summaries = runs.flatMap((run) => run.observations.map(({ summary }) => summary));
		for (const summary of summaries) {
			await expect(canvas.getByRole("button", { name: new RegExp(summary, "u") })).toHaveAttribute(
				"aria-expanded",
				"true",
			);
		}
		await expect(canvas.getAllByText("Why it was noted")).toHaveLength(summaries.length);
		await expect(
			canvas.getByText("Ask questions a teammate can answer").closest('[data-slot="badge"]'),
		).not.toBeNull();
		const question = canvas.getByRole("button", { name: /The question includes/u });
		await userEvent.click(question);
		await expect(question).toHaveAttribute("aria-expanded", "false");
		await expect(canvas.getAllByText("Why it was noted")).toHaveLength(summaries.length - 1);
	},
};

/**
 * On a practice's own level no row repeats the practice under its summary, and only the newest
 * run's row arrives open: the rest are the same practice's history, each a press away.
 */
export const OnThePracticeLevel: Story = {
	args: { showPracticeName: false, initiallyOpen: "newest" },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.queryByText("Ask questions a teammate can answer")).not.toBeInTheDocument();
		await expect(canvas.getByText("The question includes the attempted fix")).toBeVisible();

		const newest = canvas.getByRole("button", { name: /The refactor and the fix/u });
		const earlier = canvas.getByRole("button", { name: /The question includes/u });
		await expect(newest).toHaveAttribute("aria-expanded", "true");
		await expect(earlier).toHaveAttribute("aria-expanded", "false");
		await expect(canvas.getAllByText("Why it was noted")).toHaveLength(1);

		await userEvent.click(earlier);
		await expect(earlier).toHaveAttribute("aria-expanded", "true");
		await expect(newest).toHaveAttribute("aria-expanded", "true");
	},
};

/**
 * A page of the feed with more behind it: the last row's rail trails off dashed instead of
 * ending at its dot, so the "Show earlier runs" control under it reads as a continuation.
 */
export const Continues: Story = {
	args: { continues: true },
	play: async ({ canvas }) => {
		// The runs, not the observation rows inside each card, which are list items of their own.
		const [first, last] = canvas
			.getByRole("list", { name: "Observations" })
			.querySelectorAll(":scope > li");
		await expect(first?.querySelector(".border-dashed")).toBeNull();
		await expect(last?.querySelector(".border-dashed")).not.toBeNull();
	},
};
