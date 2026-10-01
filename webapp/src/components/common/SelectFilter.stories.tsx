import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { Stateful } from "@/stories/stateful";

import { SelectFilter } from "./SelectFilter";

/**
 * One labelled select in a filter toolbar. Its first choice filters nothing and reports `undefined`,
 * so no sentinel value ever reaches a URL.
 */
const meta = {
	component: SelectFilter,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		label: "Timeframe",
		allLabel: "All time",
		options: [
			{ value: "7d", label: "Last 7 days" },
			{ value: "30d", label: "Last 30 days" },
			{ value: "90d", label: "Last 90 days" },
		],
		value: undefined,
		onChange: fn(),
	},
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<SelectFilter
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
} satisfies Meta<typeof SelectFilter<string>>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Choosing an option reports its value and shows its label. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		const select = canvas.getByRole("combobox", { name: "Timeframe" });
		await expect(select).toHaveTextContent("All time");

		await userEvent.click(select);
		await userEvent.click(await screen.findByRole("option", { name: "Last 30 days" }));

		await expect(args.onChange).toHaveBeenCalledWith("30d");
		await expect(select).toHaveTextContent("Last 30 days");
	},
};

/** Choosing the first option clears the filter: the change reports `undefined`. */
export const WithSelection: Story = {
	args: { value: "7d" },
	play: async ({ args, canvas }) => {
		const select = canvas.getByRole("combobox", { name: "Timeframe" });
		await expect(select).toHaveTextContent("Last 7 days");

		await userEvent.click(select);
		await userEvent.click(await screen.findByRole("option", { name: "All time" }));

		await expect(args.onChange).toHaveBeenCalledWith(undefined);
		await expect(select).toHaveTextContent("All time");
	},
};
