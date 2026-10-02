import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { ChatComposer } from "./ChatComposer";

const meta = {
	component: ChatComposer,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<div className="mx-auto max-w-3xl">
				<Story />
			</div>
		),
	],
	args: {
		busy: false,
		onSubmit: fn(),
		onStop: fn(),
	},
} satisfies Meta<typeof ChatComposer>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Send waits for words; Shift+Enter starts a new line and Enter sends. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const send = canvas.getByRole("button", { name: "Send message" });
		await expect(send).toBeDisabled();
		const composer = canvas.getByRole("textbox", { name: "Message" });
		await expect(composer).toHaveFocus();
		await userEvent.type(composer, "First line{Shift>}{Enter}{/Shift}second line");
		await expect(send).toBeEnabled();
		await userEvent.keyboard("{Enter}");
		await expect(args.onSubmit).toHaveBeenCalledWith("First line\nsecond line");
		await expect(composer).toHaveValue("");
	},
};

/** While Heph answers, the composer keeps what the reader writes and offers to stop instead. */
export const Busy: Story = {
	args: { busy: true },
	play: async ({ args, canvas, userEvent }) => {
		const composer = canvas.getByRole("textbox", { name: "Message" });
		await userEvent.type(composer, "One more thing{Enter}");
		await expect(args.onSubmit).not.toHaveBeenCalled();
		await expect(composer).toHaveValue("One more thing");
		await userEvent.click(canvas.getByRole("button", { name: "Stop generating" }));
		await expect(args.onStop).toHaveBeenCalledOnce();
	},
};
