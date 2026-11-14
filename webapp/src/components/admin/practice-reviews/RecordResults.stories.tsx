import type { Meta, StoryObj } from "@storybook/react-vite";
import { CircleAlertIcon } from "lucide-react";
import { expect, fn, within } from "storybook/test";

import { RecordResults } from "./RecordResults";

const TABLE = { name: "Problems" } as const;

/**
 * What every record list of the Practice reviews draws before its rows: the loading table, the
 * failure with its way to retry, and an empty state that carries the way out when filters caused
 * it. The list passes the table, and `ready` hands the table nothing to say.
 */
const meta = {
	component: RecordResults,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready" },
		"aria-label": "Problems",
		columns: [{ label: "Problem" }, { label: "Severity" }],
		words: {
			errorTitle: "We could not load the problems",
			icon: CircleAlertIcon,
			emptyTitle: "No problems yet",
			filteredTitle: "No problem matches these filters",
			emptyDescription: "Problems appear here as reviews record them.",
		},
		children: (empty) => (
			<div>
				<p>The rows of the list</p>
				{empty}
			</div>
		),
	},
	argTypes: { state: { control: false }, columns: { control: false }, words: { control: false } },
} satisfies Meta<typeof RecordResults>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Ready: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("The rows of the list");
		await expect(canvas.queryByText("No problems yet")).not.toBeInTheDocument();
	},
};

/** The head stands and the rows are drawn in their shape, so nothing jumps when they arrive. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", TABLE);
		await expect(table).toHaveAttribute("aria-busy", "true");
		await expect(
			within(table)
				.getAllByRole("columnheader")
				.map((head) => head.textContent),
		).toEqual(["Problem", "Severity"]);
		await expect(canvas.queryByText("The rows of the list")).not.toBeInTheDocument();
	},
};

export const LoadFailed: Story = {
	args: { state: { status: "error", error: { status: 503 }, onRetry: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("We could not load the problems");
		await expect(canvas.queryByText("The rows of the list")).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.state.status !== "error") {
			throw new Error("This story renders the error state.");
		}
		await expect(args.state.onRetry).toHaveBeenCalledTimes(1);
	},
};

export const Empty: Story = {
	args: { state: { status: "empty", filtered: false } },
	play: async ({ canvas }) => {
		canvas.getByText("No problems yet");
		canvas.getByText("Problems appear here as reviews record them.");
		await expect(
			canvas.queryByRole("button", { name: "Clear all filters" }),
		).not.toBeInTheDocument();
	},
};

/** Filters caused the empty list, so the empty state carries the way out of them. */
export const FilteredEmpty: Story = {
	args: { state: { status: "empty", filtered: true, onClearFilters: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("No problem matches these filters");
		canvas.getByText("Change or clear the filters to see more.");
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		if (args.state.status !== "empty" || !args.state.filtered) {
			throw new Error("This story renders the filtered empty state.");
		}
		await expect(args.state.onClearFilters).toHaveBeenCalledTimes(1);
	},
};
