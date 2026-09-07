import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { ProductSurveyComposer } from "./ProductSurveyComposer";

const meta = {
	title: "Surveys/Product survey composer",
	component: ProductSurveyComposer,
	args: {
		workspaces: [{ id: 1, displayName: "Engineering" }],
		isPending: false,
		onSubmit: fn(() => Promise.resolve(true)),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof ProductSurveyComposer>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = {};
export const Publishing: Story = { args: { isPending: true } };
export const Error: Story = { args: { error: "Couldn't publish. Your draft is still here." } };
export const NoWorkspaces: Story = { args: { workspaces: [] } };
export const OptionalFollowUp: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Add question" }));
		await expect(canvas.getAllByRole("textbox", { name: "Question" })).toHaveLength(2);
		for (const checkbox of canvas.getAllByRole("checkbox", { name: "Required" }))
			await expect(checkbox).not.toBeChecked();
		await userEvent.click(canvas.getByRole("button", { name: "Remove question 1" }));
		await expect(canvas.getAllByRole("textbox", { name: "Question" })).toHaveLength(1);
	},
};
