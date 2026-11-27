import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";

import { ANSWER } from "./fixtures";
import { TeamAnswerUse } from "./TeamAnswerUse";

const meta = {
	component: TeamAnswerUse,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { used: ANSWER.answerUse, answeredAt: ANSWER.createdAt, text: ANSWER.body ?? "" },
} satisfies Meta<typeof TeamAnswerUse>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Closed until opened; it counts what the message read and says it read no private conversation. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^What Hephaestus used/u }));
		await expect(canvas.getByText("2 observations")).toBeVisible();
		await expect(canvas.getByText("One team decision")).toBeVisible();
		await expect(canvas.getByText("3 messages in this thread")).toBeVisible();
		await expect(
			canvas.getByText("It read nothing from any member’s private conversation."),
		).toBeVisible();
	},
};

/** Nothing read: the disclosure says so, and lists no empty group. */
export const Empty: Story = {
	args: { used: { observations: 0, decisions: 0, messages: 0 } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^What Hephaestus used/u }));
		await expect(
			canvas.getByText("Hephaestus read no observation, team decision or message."),
		).toBeVisible();
		await expect(canvas.queryByRole("region", { name: "Read" })).toBeNull();
	},
};
