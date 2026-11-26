import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { answeredTurn, failedTurn, sharedMessages } from "./fixtures";
import { SupportShareContent } from "./SupportShareContent";

/** What a support share shows: the person's preview and the admin's reading render this one view. */
const meta = {
	component: SupportShareContent,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { turn: failedTurn, messages: [], asker: "You" },
} satisfies Meta<typeof SupportShareContent>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A failed question names its kind of failure and the ids for the logs, never an error's text. */
export const FailedQuestion: Story = {
	play: async ({ canvas }) => {
		const facts = canvas.getByLabelText("How Hephaestus ran this question");
		await expect(facts).toHaveTextContent("The AI model or its provider stopped answering");
		await expect(facts).toHaveTextContent("chat.completion (status 503)");
		await expect(facts).toHaveTextContent(failedTurn.runId);
		await expect(canvas.queryByRole("list", { name: "Shared messages" })).toBeNull();
	},
};

/** With the messages, the question and the answer follow, each under who wrote it. */
export const WithTheMessages: Story = {
	args: { turn: answeredTurn, messages: sharedMessages, asker: "Katherine Johnson" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Katherine Johnson")).toBeVisible();
		await expect(canvas.getByText("Hephaestus")).toBeVisible();
		await expect(canvas.getByText(/waits for DNS/u)).toBeVisible();
	},
};

/** The question no longer exists: only what is left shows, here nothing about the run. */
export const QuestionGone: Story = {
	args: { turn: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByLabelText("How Hephaestus ran this question")).toBeNull();
	},
};
