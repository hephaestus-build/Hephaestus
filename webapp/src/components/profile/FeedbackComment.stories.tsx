import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";
import { FeedbackComment } from "./FeedbackComment";

const meta = {
	component: FeedbackComment,
	parameters: {
		layout: "padded",
		docs: {
			description: {
				component:
					"The written half of a developer's response to feedback. The draft stays local until it is " +
					"saved, so abandoning it leaves the recorded comment untouched — and disputing an " +
					"observation is the one case where the server insists on an explanation.",
			},
		},
	},
	tags: ["autodocs"],
	args: { isRequired: false, onSave: fn() },
	decorators: [
		(Story) => (
			<div className="max-w-lg">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof FeedbackComment>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Save comment" })).toBeDisabled();
		await expect(canvas.queryByRole("button", { name: "Cancel" })).toBeNull();
	},
};
export const Recorded: Story = {
	args: { comment: "Split into two commits so the reasoning reads on its own." },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Save comment" })).toBeDisabled();
	},
};
export const Editing: Story = {
	args: { comment: "Split into two commits." },
	play: async ({ canvas, userEvent }) => {
		const field = canvas.getByRole("textbox");
		await userEvent.type(field, " The second one is the fix.");
		await expect(canvas.getByRole("button", { name: "Save comment" })).toBeEnabled();

		await userEvent.click(canvas.getByRole("button", { name: "Cancel" }));
		await expect(field).toHaveValue("Split into two commits.");
	},
};
export const RequiredForDispute: Story = {
	args: { isRequired: true },
	play: async ({ args, canvas, userEvent }) => {
		const field = canvas.getByRole("textbox");
		await expect(field).toHaveAttribute("aria-invalid", "true");
		await expect(canvas.getByRole("button", { name: "Save comment" })).toBeDisabled();

		await userEvent.type(field, "The timeout was raised on purpose; the comment above says why.");
		await userEvent.click(canvas.getByRole("button", { name: "Save comment" }));
		await expect(args.onSave).toHaveBeenCalledWith(
			"The timeout was raised on purpose; the comment above says why.",
		);
	},
};
export const Saving: Story = {
	args: { comment: "Handled in the follow-up.", isPending: true },
};
