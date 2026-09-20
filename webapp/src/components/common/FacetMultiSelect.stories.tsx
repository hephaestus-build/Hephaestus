import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { FacetMultiSelect } from "./FacetMultiSelect";

const eventTypes = [
	{ value: "LOGIN_SUCCESS", label: "Sign-in succeeded" },
	{ value: "LOGIN_FAILURE", label: "Sign-in failed" },
	{ value: "ROLE_CHANGED", label: "Role changed" },
	{ value: "FEATURE_FLAG_CHANGED", label: "Feature flag changed" },
];

const meta = {
	component: FacetMultiSelect,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		title: "Event",
		options: eventTypes,
		selected: [],
		onChange: fn(),
	},
	decorators: [
		(Story) => (
			<div className="w-72">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof FacetMultiSelect<string>>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {};

export const TwoSelected: Story = {
	args: { selected: ["LOGIN_SUCCESS", "ROLE_CHANGED"] },
};

export const ManySelected: Story = {
	args: { selected: ["LOGIN_SUCCESS", "LOGIN_FAILURE", "ROLE_CHANGED"] },
};

export const FieldVariant: Story = {
	args: {
		variant: "field",
		title: "Workspaces",
		options: [
			{ value: "10", label: "Teaching team", description: "teaching" },
			{ value: "11", label: "Research team", description: "research" },
			{ value: "12", label: "Ärztliche Fortbildung", description: "aerzte" },
		],
	},
};

export const NoOptions: Story = {
	args: { variant: "field", title: "Workspaces", options: [], emptyLabel: "No workspaces yet" },
};

export const AccentInsensitiveSearch: Story = {
	args: {
		variant: "field",
		title: "Workspaces",
		options: [
			{ value: "10", label: "Teaching team", description: "teaching" },
			{ value: "12", label: "Ärztliche Fortbildung", description: "aerzte" },
		],
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Workspaces" }));
		await userEvent.type(await screen.findByPlaceholderText("Search…"), "arztliche");
		const options = within(await screen.findByRole("listbox", { name: "Workspaces options" }));
		await expect(await options.findByRole("option", { name: /Ärztliche/u })).toBeVisible();
		await expect(options.queryByRole("option", { name: /Teaching/u })).toBeNull();
	},
};

export const SelectsAnOption: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Event" }));
		await userEvent.click(await screen.findByRole("option", { name: "Sign-in succeeded" }));
		await expect(args.onChange).toHaveBeenCalledWith(["LOGIN_SUCCESS"]);
	},
};

export const ClearsTheSelection: Story = {
	args: { selected: ["LOGIN_SUCCESS"] },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: /Event:/u }));
		await userEvent.click(await screen.findByRole("button", { name: "Clear selection" }));
		await expect(args.onChange).toHaveBeenCalledWith([]);
	},
};
