import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { MessageEditor } from "./MessageEditor";

const meta = {
	component: MessageEditor,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		initialContent: "Can you show me what a better description looks like?",
		onCancel: fn(),
		onSend: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto max-w-2xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof MessageEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Send waits for a change; Ctrl+Enter sends it. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const editor = canvas.getByRole("textbox", { name: "Edit message" });
		await expect(editor).toHaveFocus();
		await expect(canvas.getByRole("button", { name: "Send" })).toBeDisabled();
		await userEvent.type(editor, " For a cache change.");
		await expect(canvas.getByRole("button", { name: "Send" })).toBeEnabled();
		await userEvent.keyboard("{Control>}{Enter}{/Control}");
		await expect(args.onSend).toHaveBeenCalledWith(
			"Can you show me what a better description looks like? For a cache change.",
		);
	},
};

export const CancelWithEscape: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.type(canvas.getByRole("textbox", { name: "Edit message" }), "{Escape}");
		await expect(args.onCancel).toHaveBeenCalledOnce();
		await expect(args.onSend).not.toHaveBeenCalled();
	},
};

/** A message cleared to nothing cannot be sent. */
export const Cleared: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.clear(canvas.getByRole("textbox", { name: "Edit message" }));
		await expect(canvas.getByRole("button", { name: "Send" })).toBeDisabled();
	},
};

/** The editor grows with the message up to a cap, then scrolls. */
export const LongContent: Story = {
	args: {
		initialContent: Array.from(
			{ length: 30 },
			(_, line) => `Line ${line + 1} of a long question about the practice catalog cache.`,
		).join("\n"),
	},
	play: async ({ canvas }) => {
		const editor = canvas.getByRole("textbox", { name: "Edit message" });
		await expect(editor.scrollHeight).toBeGreaterThan(editor.clientHeight);
	},
};
