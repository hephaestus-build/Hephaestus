import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { StatefulPatch } from "@/stories/stateful";

import { clearedRunFilters, ReviewRunFilters } from "./ReviewRunFilters";

/**
 * The toolbar above the Reviews list: a status, a requested-on window, and the count of what they
 * matched. It owns no data — it reports a patch and is handed the total, so the number beside it
 * always describes the answer on screen rather than the request in flight.
 *
 * The status options wear the same badges the rows do. A dropdown of plain words would make choosing
 * a filter an act of memory: matching a word here to a tag there.
 */
const meta = {
	component: ReviewRunFilters,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: {
		search: { status: undefined, resultProcessing: undefined },
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
					onReset={() => {
						args.onReset();
						onPatch(clearedRunFilters());
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
	args: {
		search: {
			status: ["COMPLETED"],
			resultProcessing: undefined,
			from: "2026-07-28",
			to: "2026-07-29",
		},
		total: 2,
	},
	play: async ({ canvas }) => {
		canvas.getByText("2 reviews match your filters.");
		canvas.getByRole("button", { name: "Requested: Jul 28 – Jul 29, 2026" });
		canvas.getByRole("button", { name: /Reset/u });
	},
};

/** One row is still "matches", not "match": the verb agrees with the count, not with the noun. */
export const OneMatch: Story = {
	args: { search: { status: ["FAILED"], resultProcessing: undefined }, total: 1 },
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
 * Choosing statuses reports the facet the reader changed, and only that: sending them back to page
 * one is the route's job, because it owns the URL. Statuses add up — failed and timed out are both
 * reviews that did not finish — rather than one replacing the other.
 */
export const ChoosingStatuses: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const trigger = canvas.getByRole("combobox", { name: "Status" });
		await userEvent.click(trigger);
		const listbox = await screen.findByRole("listbox", { name: "Status options" });
		await userEvent.click(within(listbox).getByRole("option", { name: /Failed/u }));
		await expect(args.onPatch).toHaveBeenLastCalledWith({ status: ["FAILED"] });
		await userEvent.click(within(listbox).getByRole("option", { name: /Timed out/u }));
		await expect(args.onPatch).toHaveBeenLastCalledWith({ status: ["FAILED", "TIMED_OUT"] });
		await userEvent.click(trigger);
		// Wait for the popup to finish leaving. The accessibility check runs when the play function
		// returns, and a listbox caught mid-exit has already been detached from the label that names
		// it — which under a loaded test pool is long enough to be audited.
		await waitFor(async () => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());
	},
};

/**
 * Reset clears every field the toolbar can set. The list's empty state offers the same button, and
 * both call `clearedRunFilters()` — a field added to the toolbar and forgotten in one of them would
 * leave "clear all filters" quietly keeping one.
 */
export const ResettingClearsEveryField: Story = {
	args: {
		search: {
			status: ["FAILED"],
			resultProcessing: ["FAILED"],
			from: "2026-07-28",
			to: "2026-07-29",
		},
		total: 2,
	},
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByTitle("Status: Failed");
		canvas.getByTitle("Result processing: Result processing failed");
		await userEvent.click(canvas.getByRole("button", { name: /Reset/u }));
		await expect(args.onReset).toHaveBeenCalledTimes(1);
		await expect(canvas.queryByTitle("Status: Failed")).not.toBeInTheDocument();
		await expect(
			canvas.queryByTitle("Result processing: Result processing failed"),
		).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Requested" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /Reset/u })).not.toBeInTheDocument();
		canvas.getByText("2 reviews.");
	},
};

/**
 * A review can complete and still fail to process what it produced, which its status alone reports
 * as a success — so what happened to its results is a facet of its own, beside Status.
 */
export const ChoosingAResultProcessingState: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const trigger = canvas.getByRole("combobox", { name: "Result processing" });
		await userEvent.click(trigger);
		const listbox = await screen.findByRole("listbox", { name: "Result processing options" });
		await userEvent.click(
			within(listbox).getByRole("option", { name: /Result processing failed/u }),
		);
		await expect(args.onPatch).toHaveBeenLastCalledWith({ resultProcessing: ["FAILED"] });
		await userEvent.click(trigger);
		// See `ChoosingStatuses`: the audit must not catch the listbox mid-exit.
		await waitFor(async () => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());
	},
};

/** Below `sm` the facet collapses to a count, so the applied state is a pill that clears itself. */
export const AResultProcessingStateOnAPhone: Story = {
	args: { search: { status: undefined, resultProcessing: ["FAILED"] }, total: 1 },
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("1 review matches your filters.");
		await canvas.findByTitle("Result processing: Result processing failed");
		await userEvent.click(
			canvas.getByLabelText("Clear result processing filter (Result processing failed)"),
		);
		await expect(args.onPatch).toHaveBeenCalledWith({ resultProcessing: undefined });
		await expect(
			canvas.queryByTitle("Result processing: Result processing failed"),
		).not.toBeInTheDocument();
	},
};
