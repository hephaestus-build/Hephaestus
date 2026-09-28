import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { withStandardPage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";

import { practiceGroups, reviewArtifact, workspaceMembers, workspacePractices } from "./fixtures";
import { groupFacetOptions, ObservationFilters, practiceFacetOptions } from "./ObservationFilters";
import type { ObservationsSearch } from "./review-search";
import type { ReviewPeople } from "./ReviewPersonFacet";

const PEOPLE: ReviewPeople = {
	options: workspaceMembers
		.filter((member): member is typeof member & { userId: number } => member.userId != null)
		.map((member) => ({
			userId: member.userId,
			label: member.userName ?? `#${member.userId}`,
			secondary: member.userLogin,
		})),
	capped: false,
	isLoading: false,
	isError: false,
};
const GROUPS: FacetSource = {
	options: groupFacetOptions(practiceGroups),
	isLoading: false,
	isError: false,
};
const PRACTICES: FacetSource = {
	options: practiceFacetOptions(workspacePractices, practiceGroups),
	isLoading: false,
	isError: false,
};

/** The facets a search always names, each unset. */
const UNFILTERED = {
	outcome: undefined,
	presence: undefined,
	assessment: undefined,
	severity: undefined,
} satisfies ObservationsSearch;

const meta = {
	component: ObservationFilters,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		search: UNFILTERED,
		onPatch: fn(),
		onReset: fn(),
		groups: GROUPS,
		practices: PRACTICES,
		people: PEOPLE,
		total: 12,
	},
	// Controlled: `selected` on every facet comes back through the same `search` the choice is
	// reported on, so a frozen value would leave the whole toolbar looking dead.
	render: (args) => (
		<StatefulPatch<ObservationsSearch> initial={args.search}>
			{(search, patch) => (
				<ObservationFilters
					{...args}
					search={search}
					onPatch={(next) => {
						patch(next);
						args.onPatch(next);
					}}
					onReset={() => {
						patch({
							assessmentStatus: undefined,
							outcome: undefined,
							invalidated: undefined,
							groupSlug: undefined,
							practiceSlug: undefined,
							presence: undefined,
							assessment: undefined,
							severity: undefined,
							subjectUserId: undefined,
							agentJobId: undefined,
							artifactKind: undefined,
						});
						args.onReset();
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ObservationFilters>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing set: no Reset, and the count is the whole list rather than what survived a filter. */
export const Unfiltered: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("12 observations.");
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

export const FilteredCountReadsDifferently: Story = {
	args: {
		search: { ...UNFILTERED, severity: ["MAJOR"] },
		total: 2,
	},
	play: async ({ canvas }) => {
		canvas.getByText("2 observations match your filters.");
		canvas.getByRole("button", { name: "Reset" });
	},
};

export const ReportsAChosenSeverity: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Severity" }));
		const listbox = await screen.findByRole("listbox", { name: "Severity options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Major/u }));
		await expect(args.onPatch).toHaveBeenCalledWith({ severity: ["MAJOR"] });
	},
};

/**
 * Sorting does not narrow anything, which is why it sits with the count rather than among the facets
 * and why Reset leaves it alone. The choice still travels as a patch like any other.
 */
export const SortIsNotAFilter: Story = {
	args: {
		search: { ...UNFILTERED, severity: ["MAJOR"] },
		total: 2,
	},
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: /Sort/u }));
		await userEvent.click(await screen.findByRole("option", { name: "Most actionable first" }));
		await expect(args.onPatch).toHaveBeenCalledWith({ order: "ACTIONABILITY" });

		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(canvas.getByRole("combobox", { name: /Sort/u })).toHaveTextContent(
			"Most actionable first",
		);
	},
};

/** The catalogue is still on its way, so the facet is disabled rather than offering nothing. */
export const WhileTheCatalogueLoads: Story = {
	args: {
		groups: { options: [], isLoading: true, isError: false },
		practices: { options: [], isLoading: true, isError: false },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Group" })).toBeDisabled();
		await expect(canvas.getByRole("combobox", { name: "Practice" })).toBeDisabled();
	},
};

export const TheCatalogueCouldNotBeLoaded: Story = {
	args: {
		groups: { options: [], isLoading: false, isError: true },
		practices: { options: [], isLoading: false, isError: true },
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Group" }));
		await screen.findByText("Could not load groups");
	},
};

/**
 * Arriving from a review or from a piece of work, the scope is a filter the reader did not set and
 * has to be able to see and drop. The work is named rather than printed as an id.
 */
export const ScopedToOnePieceOfWork: Story = {
	args: {
		search: {
			...UNFILTERED,
			artifactKind: "scm.pull_request",
			artifactId: 42,
		},
		scopedArtifact: reviewArtifact.reviewedWork,
		total: 5,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/Reviewed work/u);
		canvas.getByText(/ls1intum\/Hephaestus · #1423/u);
	},
};

/** Below `sm` the facet chips collapse to a count, so the applied values need their own pill row. */
export const Mobile: Story = {
	args: {
		search: { ...UNFILTERED, severity: ["MAJOR"] },
		total: 2,
	},
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas }) => {
		await canvas.findByTitle("Severity: Major");
	},
};

export const UnassessedStatuses: Story = {
	args: {
		search: {
			assessmentStatus: ["NOT_APPLICABLE", "UNDETERMINED"],
			...UNFILTERED,
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("combobox", { name: /Assessment status/u })).toHaveTextContent(
			"2",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

/**
 * Outcome is what every row's badge says, so it is the facet a count on the overview links to.
 * Behaviour is the practice's own framing of the same judgement and stays beside it.
 */
export const ReportsAChosenOutcome: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Outcome" }));
		const listbox = await screen.findByRole("listbox", { name: "Outcome options" });
		await userEvent.click(
			await within(listbox).findByRole("option", { name: /Negative outcome/u }),
		);
		await expect(args.onPatch).toHaveBeenCalledWith({ outcome: ["NEGATIVE"] });
	},
};

/** Arriving from an outcome count, the applied outcome reads as a pill at every width. */
export const AnOutcomeFromTheOverview: Story = {
	args: {
		search: {
			...UNFILTERED,
			outcome: ["POSITIVE"],
		},
		total: 4,
	},
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("4 observations match your filters.");
		await canvas.findByTitle("Outcome: Positive outcome");
		await userEvent.click(canvas.getByLabelText("Clear outcome filter (Positive outcome)"));
		await expect(args.onPatch).toHaveBeenCalledWith({ outcome: undefined });
		await expect(canvas.queryByTitle("Outcome: Positive outcome")).not.toBeInTheDocument();
	},
};

/**
 * "Marked incorrect" has no facet of its own: it only arrives from the overview's count, so it is a
 * pill the reader can see and drop rather than a control they would never reach for.
 */
export const OnlyObservationsMarkedIncorrect: Story = {
	args: {
		search: {
			invalidated: true,
			...UNFILTERED,
		},
		total: 1,
	},
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("1 observation matches your filters.");
		canvas.getByTitle("Marked incorrect: Only");
		canvas.getByRole("button", { name: "Reset" });
		await userEvent.click(
			canvas.getByRole("button", { name: "Clear marked incorrect filter (Only)" }),
		);
		await expect(args.onPatch).toHaveBeenCalledWith({ invalidated: undefined });
		await expect(canvas.queryByTitle("Marked incorrect: Only")).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};
