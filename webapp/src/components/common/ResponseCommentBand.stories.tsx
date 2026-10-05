import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { ResponseCommentBand } from "./ResponseCommentBand";

const meta = {
	component: ResponseCommentBand,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		name: "What was helpful",
		label: "What worked about this feedback?",
		placeholder: "Optional: what helped, or what you did",
		onSend: fn(),
		onSkip: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto max-w-2xl overflow-hidden rounded-xl border">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ResponseCommentBand>;

export default meta;
type Story = StoryObj<typeof meta>;

/** An optional line: Send sends whatever was typed, an empty line included, and Skip closes. */
export const Optional: Story = {
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: args.label });
		await expect(field).not.toBeRequired();
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSend).toHaveBeenCalledWith("");
		await userEvent.type(field, "   The split was the right call.");
		// Leading blanks never reach the comment.
		await expect(field).toHaveValue("The split was the right call.");
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(args.onSkip).toHaveBeenCalledOnce();
	},
};

/** A required sentence the admins read: Send does nothing until one is typed, and the field says who reads it. */
export const Required: Story = {
	args: {
		name: "Why you disagree",
		label: "What is wrong in this feedback?",
		placeholder: "One or two sentences on what is wrong",
		required: true,
		audience: "Workspace admins read your sentence, not the card.",
	},
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: args.label });
		await expect(field).toBeRequired();
		await expect(field).toHaveAccessibleDescription(args.audience);
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSend).not.toHaveBeenCalled();
		await userEvent.type(field, "#17 already split it.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSend).toHaveBeenCalledWith("#17 already split it.");
	},
};

/** The comment is on its way: Send says so and every control waits. */
export const Pending: Story = {
	args: { isPending: true, sending: true },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("button", { name: "Sending…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Skip" })).toBeDisabled();
		await expect(canvas.getByRole("textbox", { name: args.label })).toBeDisabled();
	},
};
