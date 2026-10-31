import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { heldReport } from "./fixtures";
import { ProductFeedbackDraftForm } from "./ProductFeedbackDraftForm";

const meta = {
	component: ProductFeedbackDraftForm,
	args: {
		draft: { title: heldReport.draftTitle, description: heldReport.draftDescription },
		saving: false,
		onSave: fn(async () => true),
		onCancel: fn(),
	},
	decorators: [
		(Story) => (
			<div className="max-w-lg p-4">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackDraftForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** Both fields are required: an issue with no title or no description cannot be saved. */
export const EmptyTitle: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.clear(canvas.getByRole("textbox", { name: "Issue title" }));
		await expect(canvas.getByRole("button", { name: "Save issue" })).toBeDisabled();
		await userEvent.click(canvas.getByRole("button", { name: "Cancel" }));
		await expect(args.onCancel).toHaveBeenCalledOnce();
		await expect(args.onSave).not.toHaveBeenCalled();
	},
};

export const Saving: Story = {
	args: { saving: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
		await expect(canvas.getByRole("textbox", { name: "Issue title" })).toBeDisabled();
	},
};

/** A refused save keeps the form open with the person's words in it. */
export const Refused: Story = {
	args: { onSave: fn(async () => false) },
	play: async ({ canvas, args }) => {
		await userEvent.type(canvas.getByRole("textbox", { name: "Issue title" }), " again");
		await userEvent.click(canvas.getByRole("button", { name: "Save issue" }));
		await expect(args.onSave).toHaveBeenCalledOnce();
		await expect(args.onCancel).not.toHaveBeenCalled();
		await expect(canvas.getByRole("textbox", { name: "Issue title" })).toHaveValue(
			`${heldReport.draftTitle} again`,
		);
	},
};
