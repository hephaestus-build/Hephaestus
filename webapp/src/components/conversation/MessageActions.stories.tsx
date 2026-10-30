import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import { MessageActions } from "./MessageActions";

const meta = {
	component: MessageActions,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		subject: "Hephaestus, 10:42",
		text: "Your team asks for pull request descriptions that explain significant decisions.",
		onCopy: fn(),
		vote: { value: undefined, onChange: fn() },
		onReply: fn(),
	},
	decorators: [
		(Story) => (
			// The row reveals itself on the message's hover, so the story stands in for the message.
			<div className="group/message">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof MessageActions>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Under Hephaestus's message: copy, the two votes and a reply in thread, reachable from the keyboard though hidden at rest. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const copy = canvas.getByRole("button", { name: "Copy message (Hephaestus, 10:42)" });
		await userEvent.tab();
		await expect(copy).toHaveFocus();
		await waitFor(async () => expect(copy).toBeVisible());
		await userEvent.keyboard("{Enter}");
		await expect(args.onCopy).toHaveBeenCalledWith(
			"Your team asks for pull request descriptions that explain significant decisions.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Not helpful (Hephaestus, 10:42)" }));
		await expect(args.vote?.onChange).toHaveBeenCalledWith("NOT_HELPFUL");
		await userEvent.click(
			canvas.getByRole("button", { name: "Reply in thread (Hephaestus, 10:42)" }),
		);
		await expect(args.onReply).toHaveBeenCalledOnce();
	},
};

export const Voted: Story = {
	args: { vote: { value: "HELPFUL", onChange: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		const helpful = canvas.getByRole("button", { name: "Helpful (Hephaestus, 10:42)" });
		await expect(helpful).toHaveAttribute("aria-pressed", "true");
		await expect(
			canvas.getByRole("button", { name: "Not helpful (Hephaestus, 10:42)" }),
		).toHaveAttribute("aria-pressed", "false");
		await userEvent.click(helpful);
		await expect(args.vote?.onChange).toHaveBeenCalledWith(null);
	},
};

/** Under the developer's own message: no votes, and Delete. */
export const OwnMessage: Story = {
	args: { subject: "Ada Lovelace, 10:41", vote: undefined, onDelete: fn() },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.queryByRole("button", { name: /^Helpful/u })).toBeNull();
		await userEvent.click(
			canvas.getByRole("button", { name: "Delete message (Ada Lovelace, 10:41)" }),
		);
		await expect(args.onDelete).toHaveBeenCalledOnce();
	},
};

/** A read-only conversation, such as a user view: only Copy. */
export const ReadOnly: Story = {
	args: { vote: undefined, onReply: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("button")).toHaveLength(1);
	},
};
