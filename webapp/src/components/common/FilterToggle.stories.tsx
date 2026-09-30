import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";

import { FilterToggle } from "./FilterToggle";

const OPTIONS = [
	{ value: "ALL", label: "All work", shortLabel: "All", srSuffix: "work" },
	{ value: "scm.pull_request", label: "Pull or merge requests" },
	{ value: "scm.issue", label: "Issues" },
	{ value: "docs.document", label: "Documents" },
] as const;

const meta = {
	component: FilterToggle,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { label: "Filter by kind of work", options: OPTIONS, value: "ALL", onChange: fn() },
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<FilterToggle
					{...args}
					value={value}
					onChange={(next) => {
						args.onChange(next);
						setValue(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof FilterToggle>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const toolbar = canvas.getByRole("toolbar", { name: "Filter by kind of work" });
		await userEvent.click(within(toolbar).getByRole("button", { name: "Issues" }));
		await expect(args.onChange).toHaveBeenCalledWith("scm.issue");
	},
};

/** The chip is shortened to fit the row; the accessible name still names the filter in full. */
export const ShortenedChipKeepsItsName: Story = {
	play: async ({ canvas }) => {
		const toolbar = canvas.getByRole("toolbar", { name: "Filter by kind of work" });
		await expect(within(toolbar).getByRole("button", { name: "All work" })).toBeVisible();
	},
};

export const Chosen: Story = {
	args: { value: "docs.document" },
	play: async ({ canvas }) => {
		const toolbar = canvas.getByRole("toolbar", { name: "Filter by kind of work" });
		const pressed = within(toolbar)
			.getAllByRole("button")
			.filter((button) => button.getAttribute("aria-pressed") === "true");
		await expect(pressed.map((button) => button.textContent)).toEqual(["Documents"]);
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		// The toggle row is display-none here, so the select is the whole control.
		await expect(canvas.getByRole("combobox", { name: "Filter by kind of work" })).toBeVisible();
	},
};

export const DarkMode: Story = { globals: { theme: "dark" } };
