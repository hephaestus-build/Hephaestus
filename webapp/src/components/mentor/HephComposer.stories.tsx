import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { RemovableToken } from "@/components/common/RemovableToken";

import { HephComposer } from "./HephComposer";

const meta = {
	component: HephComposer,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { label: "Message Heph", placeholder: "Message Heph", onSend: fn() },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof HephComposer>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Enter sends; Shift+Enter starts a new line; an empty message does not send. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const field = canvas.getByRole("textbox", { name: "Message Heph" });
		await expect(canvas.getByRole("button", { name: "Send message" })).toBeDisabled();
		await userEvent.type(field, "First line{Shift>}{Enter}{/Shift}second line");
		await expect(args.onSend).not.toHaveBeenCalled();
		await userEvent.keyboard("{Enter}");
		await expect(args.onSend).toHaveBeenCalledWith("First line\nsecond line");
		await expect(field).toHaveValue("");
	},
};

/**
 * Heph is answering. Send stays: a message written now waits as a follow-up. Stop sits beside it
 * rather than replacing it, so stopping and asking are never the same press.
 */
export const Busy: Story = {
	args: { stop: { label: "Stop Heph", onStop: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Message Heph" }),
			"And one more{Enter}",
		);
		await expect(args.onSend).toHaveBeenCalledWith("And one more");
		await userEvent.click(canvas.getByRole("button", { name: "Stop Heph" }));
		await expect(args.stop?.onStop).toHaveBeenCalledOnce();
	},
};

/** In the Heph panel: the page the developer asks from travels with the message until they remove it. */
export const WithContext: Story = {
	args: {
		context: (
			<RemovableToken
				label="From Practice profile"
				removeLabel="Do not send this page with your message"
				onRemove={fn()}
			/>
		),
	},
};
