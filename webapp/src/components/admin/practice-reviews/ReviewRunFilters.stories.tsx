import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { StatefulPatch } from "@/stories/stateful";

import { ReviewRunFilters } from "./ReviewRunFilters";

/**
 * The toolbar above the Reviews list: a status, a requested-on window, and the count of what they
 * matched. It owns no data — it reports a patch and is handed the total, so the number beside it
 * always describes the answer on screen rather than the request in flight.
 *
 * The status options wear the same badges the rows do. A dropdown of plain words would make choosing
 * a filter an act of memory: matching a word here to a tag there.
 */
const meta = {
	title: "Workspace admin/Practice reviews/Building blocks/Review run filters",
	component: ReviewRunFilters,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: {
		search: {},
		onPatch: fn(),
		onReset: fn(),
		total: 7,
	},
	// Controlled, so a frozen `search` would leave every control inert while nothing looks broken.
	// Nothing here recomputes "is anything filtered" — the toolbar derives that from the `search` it
	// is already given, so a story cannot hand it a search and a contradicting answer about it.
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, onPatch) => (
				<ReviewRunFilters
					{...args}
					search={search}
					onPatch={(patch) => {
						args.onPatch(patch);
						onPatch(patch);
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ReviewRunFilters>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing chosen: the count says how many reviews exist, and there is nothing to reset. */
export const Unfiltered: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("7 reviews.");
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

/** Chosen: the count says what survived, and Reset appears to undo all of it at once. */
export const Filtered: Story = {
	args: { search: { status: "COMPLETED", from: "2026-07-28", to: "2026-07-29" }, total: 2 },
	play: async ({ canvas }) => {
		canvas.getByText("2 reviews match your filters.");
		canvas.getByRole("button", { name: "Requested: Jul 28 – Jul 29, 2026" });
		canvas.getByRole("button", { name: /Reset/u });
	},
};

/** One row is still "matches", not "match": the verb agrees with the count, not with the noun. */
export const OneMatch: Story = {
	args: { search: { status: "FAILED" }, total: 1 },
	play: async ({ canvas }) => {
		canvas.getByText("1 review matches your filters.");
	},
};

/**
 * No total yet. The count renders nothing rather than a zero, because a zero here would announce an
 * empty list a moment before the full one arrives.
 */
export const CountNotInYet: Story = {
	args: { total: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("status")).not.toBeInTheDocument();
	},
};

/**
 * Choosing a status reports the facet the reader changed, and only that. Sending them back to page
 * one is the screen's job — it owns the URL, and its two siblings already did it there, so a
 * `page: 0` folded in here would be one toolbar in three with a second contract.
 */
export const ChoosingAStatus: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox"));
		const listbox = await screen.findByRole("listbox");
		await userEvent.click(within(listbox).getByRole("option", { name: /Failed/u }));

		await expect(args.onPatch).toHaveBeenCalledWith({ status: "FAILED" });
		await expect(canvas.getByRole("combobox")).toHaveTextContent("Failed");
		// Wait for the popup to finish leaving. The accessibility check runs when the play function
		// returns, and a listbox caught mid-exit has already been detached from the label that names
		// it — which under a loaded test pool is long enough to be audited.
		await waitFor(() => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());
	},
};

/**
 * Reset clears every field the toolbar can set. The list's empty state offers the same button, and
 * both call `clearedRunFilters()` — a field added to the toolbar and forgotten in one of them would
 * leave "clear all filters" quietly keeping one.
 */
export const ResettingClearsEveryField: Story = {
	args: { search: { status: "FAILED", from: "2026-07-28", to: "2026-07-29" }, total: 2 },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Reset/u }));
		await expect(args.onReset).toHaveBeenCalledTimes(1);
	},
};
