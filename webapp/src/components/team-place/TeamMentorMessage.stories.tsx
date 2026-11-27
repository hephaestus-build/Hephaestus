import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { precedes } from "@/test/dom";

import { ANSWER, UNAVAILABLE_CITATION } from "./fixtures";
import { TeamMentorMessage } from "./TeamMentorMessage";

const meta = {
	component: TeamMentorMessage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { message: ANSWER, anchorLink: () => <a href="#version-1" /> },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof TeamMentorMessage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** An answer cites places in the reviewed version, then lists What Hephaestus used. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hephaestus", { exact: true })).toBeVisible();
		await expect(canvas.getByText("AI", { exact: true })).toBeVisible();
		await expect(
			canvas.getByRole("link", { name: "Students × Define in version 1" }),
		).toHaveAttribute("href", "#version-1");
		const lastCitation = canvas.getByRole("link", { name: "Students × Ideate in version 1" });
		const used = canvas.getByRole("button", { name: /^What Hephaestus used/u });
		await expect(precedes(lastCitation, used)).toBe(true);
	},
};

/** The feedback posted when the review sealed renders the same way. */
export const ReviewFeedback: Story = {
	args: { message: { ...ANSWER, kind: "REVIEW_FEEDBACK" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Cited in the work" })).toBeVisible();
	},
};

/** Options and other parts that a caller adds sit between the citations and What Hephaestus used. */
export const WithOptions: Story = {
	render: (args) => (
		<TeamMentorMessage {...args}>
			<p className="text-sm">An option from the caller</p>
		</TeamMentorMessage>
	),
	play: async ({ canvas }) => {
		const option = canvas.getByText("An option from the caller");
		const used = canvas.getByRole("button", { name: /^What Hephaestus used/u });
		await expect(precedes(option, used)).toBe(true);
	},
};

/** A cited place whose source is gone says so, opens nothing, and shows no old label. */
export const CitedSourceGone: Story = {
	args: {
		message: { ...ANSWER, citations: [...ANSWER.citations.slice(0, 1), UNAVAILABLE_CITATION] },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No longer available")).toBeVisible();
		await expect(canvas.getAllByRole("link")).toHaveLength(1);
	},
};

/** Hephaestus did not write the review's feedback: one fixed line, with no cause. */
export const NotMentoringThisReview: Story = {
	args: {
		message: {
			...ANSWER,
			kind: "REVIEW_FEEDBACK",
			status: "NOT_COMPOSED",
			body: undefined,
			citations: [],
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Hephaestus is not mentoring this review. The observations are below."),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /What Hephaestus used/u })).toBeNull();
	},
};

/** Hephaestus did not answer a question: the refusal says only that it cannot answer here. */
export const CannotAnswerHere: Story = {
	args: { message: { ...ANSWER, status: "NOT_COMPOSED", body: undefined, citations: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hephaestus cannot answer here.")).toBeVisible();
	},
};

/** A message whose cited source is gone is hidden: no words, places or list of what it used. */
export const Stale: Story = {
	args: {
		message: { ...ANSWER, status: "STALE", body: undefined, citations: [UNAVAILABLE_CITATION] },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("This message is hidden because a source it cited is no longer available."),
		).toBeVisible();
		await expect(canvas.queryByRole("region", { name: "Cited in the work" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: /What Hephaestus used/u })).toBeNull();
	},
};
