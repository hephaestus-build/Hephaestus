import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { minutesAfter, minutesBefore } from "@/stories/story-clock";

import { DraftingProgress } from "./DraftingProgress";

const meta = {
	component: DraftingProgress,
	args: {
		since: minutesBefore(2),
		deadline: minutesAfter(10),
		waitingForModel: false,
		wantsPublic: false,
		reader: "reporter",
	},
	tags: ["autodocs"],
} satisfies Meta<typeof DraftingProgress>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A model drafts the report: since when, and about when it goes on without a draft. */
export const Drafting: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/An AI model is drafting the issue\. Started/u)).toBeVisible();
		await expect(canvas.getByText(/If the draft is not ready by about/u)).toHaveTextContent(
			/^If the draft is not ready by about \d{1,2}:\d{2} [ap]m, your report goes on as written\.$/u,
		);
	},
};

/** The model is busy with other work; the report still goes on by the same time. */
export const WaitingForModel: Story = {
	args: { waitingForModel: true },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/busy with other work, so the draft waits for it\. Started/u),
		).toBeVisible();
		await expect(canvas.getByText(/If the draft is not ready by about/u)).toBeVisible();
	},
};

/** A report that asks for a public issue learns that it stays private without a draft. */
export const WantsPublic: Story = {
	args: { wantsPublic: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/If the draft is not ready by about/u)).toHaveTextContent(
			/goes on as written and does not become public\.$/u,
		);
	},
};

/** The admin console names the report, not the reader's own. */
export const ForAnAdmin: Story = {
	args: { reader: "admin" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/If the draft is not ready by about/u)).toHaveTextContent(
			/, the report goes on as written\.$/u,
		);
	},
};

/** No model drafts it: no model, start or time is claimed. */
export const NoModelAtWork: Story = {
	args: { since: undefined, deadline: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hephaestus prepares the issue from this report.")).toBeVisible();
		await expect(canvas.queryByText(/AI model|Started|not ready by/u)).toBeNull();
	},
};
