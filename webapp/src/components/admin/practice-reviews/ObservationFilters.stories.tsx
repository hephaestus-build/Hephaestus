import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { withStandardPage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";

import { groupFacetOptions, ObservationFilters, practiceFacetOptions } from "./ObservationFilters";
import type { ObservationsSearch } from "./review-search";
import type { ReviewPeople } from "./ReviewPersonFacet";
import {
	practiceGroups,
	reviewArtifact,
	workspaceMembers,
	workspacePractices,
} from "./story-mock-data";

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

const meta = {
	title: "Workspace admin/Practice reviews/Building blocks/Observation filters",
	component: ObservationFilters,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		search: { presence: undefined, assessment: undefined, severity: undefined },
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
		search: { presence: undefined, assessment: undefined, severity: ["MAJOR"] },
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
		await userEvent.click(await within(listbox).findByRole("option", { name: /Major/ }));
		await expect(args.onPatch).toHaveBeenCalledWith({ severity: ["MAJOR"] });
	},
};

/**
 * Sorting does not narrow anything, which is why it sits with the count rather than among the facets
 * and why Reset leaves it alone. The choice still travels as a patch like any other.
 */
export const SortIsNotAFilter: Story = {
	args: { search: { presence: undefined, assessment: undefined, severity: ["MAJOR"] }, total: 2 },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: /Sort/ }));
		await userEvent.click(await screen.findByRole("option", { name: "Most actionable first" }));
		await expect(args.onPatch).toHaveBeenCalledWith({ order: "ACTIONABILITY" });

		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(canvas.getByRole("combobox", { name: /Sort/ })).toHaveTextContent(
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
			presence: undefined,
			assessment: undefined,
			severity: undefined,
			artifactKind: "scm.pull_request",
			artifactId: 42,
		},
		scopedArtifact: reviewArtifact,
		total: 5,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/Reviewed work/);
		canvas.getByText(/PR #1423/);
	},
};

/** Below `sm` the facet chips collapse to a count, so the applied values need their own pill row. */
export const Mobile: Story = {
	args: {
		search: { presence: undefined, assessment: undefined, severity: ["MAJOR"] },
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
			presence: undefined,
			assessment: undefined,
			severity: undefined,
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("combobox", { name: /Assessment status/ })).toHaveTextContent(
			"2",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};
