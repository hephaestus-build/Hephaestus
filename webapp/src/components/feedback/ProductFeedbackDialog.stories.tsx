import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/test/overlay";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";

const onSubmit = fn(() => Promise.resolve(true));
const meta = {
	title: "Surveys/Product feedback dialog",
	component: ProductFeedbackDialog,
	args: { isSubmitting: false, onSubmit },
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SubmitFeedback: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Send product feedback" }));
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(
			dialog.getByRole("textbox", { name: "Message" }),
			"The survey flow is clear.",
		);
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith(
			"FEEDBACK",
			"The survey flow is clear.",
			false,
		);
	},
};

export const Default: Story = {};
export const Sending: Story = {
	args: { isSubmitting: true },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Send product feedback" }));
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("button", { name: "Sending…" }));
		await expect(dialog.getByRole("textbox", { name: "Message" })).toBeDisabled();
	},
};
export const Error: Story = {
	args: {
		error: "Couldn't send. Your draft is still here.",
		onSubmit: fn(() => Promise.resolve(false)),
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Send product feedback" }));
		await expectSettledVisible(within(await screen.findByRole("dialog")).getByRole("alert"));
	},
};
export const PageContext: Story = {
	args: { pagePath: "/w/engineering/practices" },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Send product feedback" }));
		const dialog = within(await screen.findByRole("dialog"));
		const checkbox = dialog.getByRole("checkbox", { name: "Include current page path" });
		await expect(checkbox).not.toBeChecked();
		await userEvent.click(checkbox);
		await userEvent.type(dialog.getByRole("textbox", { name: "Message" }), "Show more context");
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith("FEEDBACK", "Show more context", true);
	},
};
