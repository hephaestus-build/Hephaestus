import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

import { FormErrorSummary } from "./FormErrorSummary";

const meta = {
	component: FormErrorSummary,
	parameters: { layout: "padded" },
	args: {
		errors: [
			{
				fieldId: "practice-name",
				message: "Give the practice a name of at least three characters.",
			},
			{ fieldId: "practice-criteria", message: "Say what this practice checks." },
		],
	},
	decorators: [
		(Story) => (
			<div className="space-y-6">
				<Story />
				<div className="space-y-2">
					<Label htmlFor="practice-name">Name</Label>
					<Input id="practice-name" />
					<Label htmlFor="practice-criteria">What to look for</Label>
					<Input id="practice-criteria" />
				</div>
			</div>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof FormErrorSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// A live region, so the refusal is announced without competing for focus with the field the
		// form sends the reader to.
		canvas.getByRole("alert");
		canvas.getByRole("heading", { name: "There are 2 problems" });
	},
};

export const EntryMovesFocusToItsField: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("link", { name: /Give the practice a name/u }));
		// A frame later: an entry may first have to reveal a collapsed section for its field to exist.
		await waitFor(() => expect(canvas.getByLabelText("Name")).toHaveFocus());
	},
};

const collapsedSlugError = {
	fieldId: "hidden-field",
	message: "The identifier must be lowercase letters, numbers and hyphens.",
	reveal: fn(),
};

export const EntryRevealsACollapsedSectionFirst: Story = {
	args: { errors: [collapsedSlugError] },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("link", { name: /identifier/u }));
		await expect(collapsedSlugError.reveal).toHaveBeenCalledOnce();
	},
};

export const SingleProblem: Story = {
	args: { errors: [{ fieldId: "practice-name", message: "Give the practice a name." }] },
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "There is a problem" });
	},
};

export const NoProblems: Story = {
	args: { errors: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
	},
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
