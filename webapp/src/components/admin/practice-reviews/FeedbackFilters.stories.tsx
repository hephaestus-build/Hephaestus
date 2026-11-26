import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { withStandardPage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";

import { clearedFeedbackFacets, FeedbackFilters } from "./FeedbackFilters";
import { practiceGroups, workspacePractices } from "./fixtures";
import { practiceFacetOptions } from "./ResultFilters";
import { clearedScope } from "./review-scope";
import type { FeedbackSearch } from "./review-search";
import { STORY_GROUPS } from "./story-scope-sources";

const UNFILTERED = {
	state: undefined,
	destination: undefined,
	withheldFamily: undefined,
} satisfies FeedbackSearch;

const PRACTICES: FacetSource = {
	options: practiceFacetOptions(workspacePractices, practiceGroups),
	isLoading: false,
	isError: false,
};

/**
 * The Feedback list's toolbar, on its own. It reports a patch and renders what it is given; which
 * rows come back is the route's business, so every state here is a `search` value rather than a
 * response.
 */
const meta = {
	component: FeedbackFilters,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		search: UNFILTERED,
		onPatch: fn(),
		onReset: fn(),
		onClearScope: fn(),
		groups: STORY_GROUPS,
		practices: PRACTICES,
		total: 11,
	},
	// Controlled: `selected` on every facet comes back through the same `search` the choice is
	// reported on, so a frozen value would leave the whole toolbar looking dead.
	render: (args) => (
		<StatefulPatch<FeedbackSearch> initial={args.search}>
			{(search, patch) => (
				<FeedbackFilters
					{...args}
					search={search}
					onPatch={(next) => {
						patch(next);
						args.onPatch(next);
					}}
					onReset={() => {
						patch(clearedFeedbackFacets());
						args.onReset();
					}}
					onClearScope={() => {
						patch(clearedScope());
						args.onClearScope();
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof FeedbackFilters>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing set: no Reset, and the count is the whole list rather than what survived a filter. */
export const Unfiltered: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("11 pieces of feedback.");
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

/** Reset appears as soon as anything is set, and the count changes its wording with it. */
export const FilteredCountReadsDifferently: Story = {
	args: {
		search: { ...UNFILTERED, state: ["DELIVERED"] },
		total: 3,
	},
	play: async ({ canvas }) => {
		canvas.getByText("3 pieces of feedback match your filters.");
		canvas.getByRole("button", { name: "Reset" });
	},
};

/** One row of feedback is still a sentence that agrees with itself. */
export const ExactlyOneMatch: Story = {
	args: {
		search: { ...UNFILTERED, state: ["DELIVERED"] },
		total: 1,
	},
	play: async ({ canvas }) => {
		canvas.getByText("1 piece of feedback matches your filters.");
	},
};

export const ReportsAChosenDelivery: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Delivery" }));
		const listbox = await screen.findByRole("listbox", { name: "Delivery options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /^Delivered/u }));
		await expect(args.onPatch).toHaveBeenCalledWith({ state: ["DELIVERED"] });
	},
};

/** Below `sm` the facet chips collapse to a count, so the applied values need their own pill row. */
export const Mobile: Story = {
	args: {
		search: { ...UNFILTERED, state: ["DELIVERED", "WITHHELD"] },
		total: 7,
	},
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas }) => {
		await canvas.findByTitle("Delivery: Delivered");
	},
};

export const ReportsAChosenPractice: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Practice" }));
		const listbox = await screen.findByRole("listbox", { name: "Practice options" });
		await userEvent.click(
			await within(listbox).findByRole("option", { name: /Thin controllers/u }),
		);
		await expect(args.onPatch).toHaveBeenCalledWith({ practiceSlug: ["thin-controllers"] });
	},
};

/** Arriving from a practice's count, the practice reads as a pill at every width and clears there. */
export const APracticeOnAPhone: Story = {
	args: {
		search: { ...UNFILTERED, practiceSlug: ["thin-controllers"] },
		total: 3,
	},
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("3 pieces of feedback match your filters.");
		await canvas.findByTitle("Practice: Thin controllers");
		await userEvent.click(canvas.getByLabelText("Clear practice filter (Thin controllers)"));
		await expect(args.onPatch).toHaveBeenCalledWith({ practiceSlug: undefined });
		await expect(canvas.queryByTitle("Practice: Thin controllers")).not.toBeInTheDocument();
	},
};

/** The practices are still on their way, so the facet is disabled rather than offering nothing. */
export const WhileThePracticesLoad: Story = {
	args: { practices: { options: [], isLoading: true, isError: false } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Practice" })).toBeDisabled();
	},
};

export const ThePracticesCouldNotBeLoaded: Story = {
	args: { practices: { options: [], isLoading: false, isError: true } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Practice" }));
		await screen.findByText("We could not load practices");
	},
};

export const NothingMatches: Story = {
	args: { search: { ...UNFILTERED, state: ["FAILED"] }, total: 0 },
	play: async ({ canvas }) => {
		canvas.getByText("0 pieces of feedback match your filters.");
	},
};

// The time order is the shared scope's: it sits above the tabs, Reset keeps it, "Clear scope" ends it.
export const SortIsNotAFilter: Story = {
	args: {
		search: { ...UNFILTERED, state: ["AWAITING_APPROVAL"] },
		total: 3,
	},
	play: async ({ args, canvas, userEvent }) => {
		const scope = canvas.getByRole("group", { name: "Applies to every tab" });
		const sort = within(scope).getByRole("combobox", { name: /Sort/u });
		await expect(
			within(canvas.getByRole("group", { name: "Filters" })).queryByRole("combobox", {
				name: /Sort/u,
			}),
		).toBeNull();
		await expect(sort).toHaveTextContent("Newest first");
		await expect(within(scope).queryByRole("button", { name: "Clear scope" })).toBeNull();
		await userEvent.click(sort);
		await userEvent.click(await screen.findByRole("option", { name: "Oldest first" }));
		await expect(args.onPatch).toHaveBeenCalledWith({ order: "OLDEST" });
		// The leaving popup still blocks the pointer under a loaded test pool, so Reset waits for it.
		await waitFor(async () => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());

		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(sort).toHaveTextContent("Oldest first");

		await userEvent.click(sort);
		await userEvent.click(await screen.findByRole("option", { name: "Newest first" }));
		await expect(args.onPatch).toHaveBeenLastCalledWith({ order: undefined });
		await waitFor(async () => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());

		await userEvent.click(sort);
		await userEvent.click(await screen.findByRole("option", { name: "Oldest first" }));
		await waitFor(async () => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());
		await userEvent.click(within(scope).getByRole("button", { name: "Clear scope" }));
		await expect(args.onClearScope).toHaveBeenCalled();
		await expect(sort).toHaveTextContent("Newest first");
	},
};

export const ReportsAChosenGroup: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const group = STORY_GROUPS.options[0];
		await userEvent.click(canvas.getByRole("combobox", { name: "Group" }));
		const listbox = await screen.findByRole("listbox", { name: "Group options" });
		await userEvent.click(
			await within(listbox).findByRole("option", { name: new RegExp(group?.label ?? "", "u") }),
		);
		await expect(args.onPatch).toHaveBeenCalledWith({ groupSlug: [group?.value] });
	},
};

export const EveryFacetInOneRow: Story = {
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByRole("combobox", { name: "Delivery" });
		canvas.getByRole("combobox", { name: "Destination" });
		canvas.getByRole("combobox", { name: /Sort/u });
		await expect(canvas.queryByRole("button", { name: /More filters/u })).toBeNull();
		await userEvent.click(canvas.getByRole("combobox", { name: "Why withheld" }));
		const listbox = await screen.findByRole("listbox", { name: "Why withheld options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Housekeeping/u }));
		await expect(args.onPatch).toHaveBeenCalledWith({ withheldFamily: ["HOUSEKEEPING"] });
	},
};
