import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { split } from "@/stories/practices-across-the-workspace-story-data";

import { PracticePill } from "@/components/practice-vocabulary/PracticePill";

import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";

const row = (
	key: string,
	name: string,
	rowSplit: ComparisonRow["split"],
	yourStanding?: ComparisonRow["yourStanding"],
	yourBasis?: ComparisonRow["yourBasis"],
): ComparisonRow => ({
	key,
	name,
	subject: <PracticePill name={name} />,
	yourStanding,
	yourBasis,
	split: rowSplit,
});

const ROWS: ComparisonRow[] = [
	row("explain", "Explain the change", split(6, 7, 9, 6), "STRENGTH", "CURRENT_REVIEWS"),
	row("scope", "Scope the change", split(1, 0, 2, 25), "NOT_OBSERVED"),
	row("history", "Keep the history readable", split(4, 5, 12, 7), "MIXED", "EARLIER_REVIEWS"),
];

/** Practice groups or practices, each beside its split, the reader marked where counted. */
const meta = {
	component: WorkspaceComparisonTable,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: {
		"aria-label": "Practices",
		subjectHead: "Practice",
		state: { status: "ready", rows: ROWS },
		rowLink: () => ({ text: "Open", onOpen: fn() }),
		empty: { title: "No practices yet", description: "They appear once reviewed." },
	},
} satisfies Meta<typeof WorkspaceComparisonTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("img", {
				name: "28 developers in this workspace: 4 Needs attention, 5 Mixed feedback, 12 Going well, 7 none yet. You are in the Mixed feedback part.",
			}),
		).toBeVisible();
		// Only the row whose own standing rests on earlier reviews says so.
		await expect(
			canvas.getAllByText("Based on earlier reviews, the criteria might have changed"),
		).toHaveLength(1);
	},
};

/** The mark on an earlier-reviews row is words only: no control of its own competes with the row. */
export const EarlierReviewsMarkIsWordsOnly: Story = {
	args: { rowLink: () => ({ text: "Open", onOpen: fn() }) },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^Based on earlier reviews/u })).toBeNull();
		await expect(
			canvas.getByText("Based on earlier reviews, the criteria might have changed"),
		).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table")).toHaveAttribute("aria-busy", "true");
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", rows: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practices yet")).toBeVisible();
	},
};
