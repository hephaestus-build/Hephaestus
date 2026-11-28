import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";

import {
	ACTED_ON_DECISIONS,
	DISMISSED_RECORD,
	FORMER_MEMBER_RECORD,
	OPEN_RECORD,
} from "./decision-fixtures";
import { TeamDecisionRecord } from "./TeamDecisionRecord";

const meta = {
	component: TeamDecisionRecord,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { decisions: ACTED_ON_DECISIONS, questionLink: <a href="#decision-thread" /> },
	decorators: [
		(Story) => (
			<div className="max-w-xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof TeamDecisionRecord>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Acted on, with the note in the recording member's words, who recorded it, and the record it superseded. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Acted on")).toBeVisible();
		await expect(canvas.getByText(/We will check the step-free route/u)).toBeVisible();
		await expect(canvas.getByText(/^Recorded by Ada Lovelace · .* · version 1$/u)).toBeVisible();
		await expect(canvas.queryByText(/agreed/u)).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "One earlier record" }));
		await expect(canvas.getByText(/^Recorded by Alan Turing/u)).toBeVisible();
	},
};

/** Another member questions the decision: the link opens its entry in the thread. */
export const QuestionThisDecision: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Question this decision" })).toHaveAttribute(
			"href",
			"#decision-thread",
		);
	},
};

/** Declined: dismissed, with the reason in the team's words. */
export const Dismissed: Story = {
	args: { decisions: { current: DISMISSED_RECORD, earlier: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Dismissed")).toBeVisible();
		await expect(canvas.getByText(/Out of scope for this course/u)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /earlier record/u })).toBeNull();
	},
};

/** An open decision recorded without a note shows no empty note. */
export const OpenWithoutNote: Story = {
	args: { decisions: { current: OPEN_RECORD, earlier: [] }, questionLink: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Open")).toBeVisible();
		await expect(canvas.queryByRole("link", { name: "Question this decision" })).toBeNull();
	},
};

/** A decision whose recorder was erased keeps the decision and drops the name. */
export const RecordedByFormerMember: Story = {
	args: { decisions: { current: FORMER_MEMBER_RECORD, earlier: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^Recorded by a former member · /u)).toBeVisible();
	},
};
