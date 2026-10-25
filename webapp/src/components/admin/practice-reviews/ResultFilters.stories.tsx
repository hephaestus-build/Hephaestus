import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";

import { practiceGroups, workspacePractices } from "./fixtures";
import { clearedResultFacets, ResultFilters } from "./ResultFilters";
import { clearedScope } from "./review-scope";
import type { ResultsSearch } from "./review-search";
import { STORY_GROUPS, STORY_PEOPLE, STORY_PRACTICES } from "./story-scope-sources";

const UNFILTERED = {
	outcome: undefined,
	severity: undefined,
	disagreed: undefined,
} satisfies ResultsSearch;

const meta = {
	component: ResultFilters,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		search: UNFILTERED,
		onPatch: fn(),
		onReset: fn(),
		onClearScope: fn(),
		groups: STORY_GROUPS,
		practices: STORY_PRACTICES,
		people: STORY_PEOPLE,
		total: 12,
	},
	render: (args) => (
		<StatefulPatch<ResultsSearch> initial={args.search}>
			{(search, patch) => (
				<ResultFilters
					{...args}
					search={search}
					onPatch={(next) => {
						patch(next);
						args.onPatch(next);
					}}
					onReset={() => {
						patch(clearedResultFacets());
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
} satisfies Meta<typeof ResultFilters>;

export default meta;
type Story = StoryObj<typeof meta>;

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
		await expect(canvas.queryByRole("button", { name: /More filters/u })).toBeNull();
		await userEvent.click(canvas.getByRole("combobox", { name: "Severity" }));
		const listbox = await screen.findByRole("listbox", { name: "Severity options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Major/u }));
		await expect(args.onPatch).toHaveBeenCalledWith({ severity: ["MAJOR"] });
	},
};

// "Most actionable first" follows the shared time order in the scope bar's select, as this list's own.
export const OwnOrderAfterTheTimeOrder: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const scope = canvas.getByRole("group", { name: "Applies to every tab" });
		await userEvent.click(within(scope).getByRole("combobox", { name: /Sort/u }));
		const options = await screen.findAllByRole("option");
		await expect(options.map((option) => option.textContent)).toStrictEqual([
			"Newest first",
			"Oldest first",
			"Most actionable first",
		]);
		await userEvent.click(await screen.findByRole("option", { name: "Oldest first" }));
		await expect(args.onPatch).toHaveBeenCalledWith({ order: "OLDEST" });
	},
};

// Sorting does not narrow the list, so Reset must preserve it.
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
		await screen.findByText("We could not load groups");
	},
};

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
			...UNFILTERED,
			outcome: ["DOES_NOT_APPLY", "COULD_NOT_DETERMINE"],
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("combobox", { name: /Outcome/u })).toHaveTextContent("2");
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

export const ReportsAChosenOutcome: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Outcome" }));
		const listbox = await screen.findByRole("listbox", { name: "Outcome options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Not met/u }));
		await expect(args.onPatch).toHaveBeenCalledWith({ outcome: ["NOT_MET"] });
	},
};

export const AnOutcomeFromTheOverview: Story = {
	args: {
		search: {
			...UNFILTERED,
			outcome: ["MET"],
		},
		total: 4,
	},
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("4 observations match your filters.");
		await canvas.findByTitle("Outcome: Met");
		await userEvent.click(canvas.getByLabelText("Clear outcome filter (Met)"));
		await expect(args.onPatch).toHaveBeenCalledWith({ outcome: undefined });
		await expect(canvas.queryByTitle("Outcome: Met")).not.toBeInTheDocument();
	},
};

export const OnlyResultsMarkedIncorrect: Story = {
	args: {
		search: {
			markedIncorrect: true,
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
		await expect(args.onPatch).toHaveBeenCalledWith({ markedIncorrect: undefined });
		await expect(canvas.queryByTitle("Marked incorrect: Only")).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

export const ReportsAChosenDisagreement: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Disagreement" }));
		const listbox = await screen.findByRole("listbox", { name: "Disagreement options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Disagreed/u }));
		await expect(args.onPatch).toHaveBeenCalledWith({ disagreed: true });
	},
};

export const ADisagreementOnAPhone: Story = {
	args: { search: { ...UNFILTERED, disagreed: false }, total: 2 },
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("2 observations match your filters.");
		await canvas.findByTitle("Disagreement: No disagreement");
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(args.onReset).toHaveBeenCalledTimes(1);
		await expect(canvas.queryByTitle("Disagreement: No disagreement")).not.toBeInTheDocument();
	},
};

export const SetFacetShowsItsValue: Story = {
	args: { search: { ...UNFILTERED, severity: ["MAJOR"] }, total: 2 },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: /Severity/u })).toHaveTextContent("Major");
	},
};

export const ScopeCarriedFromAnotherTab: Story = {
	args: {
		search: {
			...UNFILTERED,
			outcome: ["NOT_MET"],
			developerId: STORY_PEOPLE.options[0]?.accountId,
			practiceSlug: [workspacePractices[0]?.slug ?? ""],
			from: "2026-09-01",
			to: "2026-09-10",
		},
		total: 3,
	},
	play: async ({ args, canvas, userEvent }) => {
		const scope = canvas.getByRole("group", { name: "Applies to every tab" });
		await expect(
			within(scope).getByRole("combobox", { name: /^Developer(?::|$)/u }),
		).toHaveTextContent(STORY_PEOPLE.options[0]?.label ?? "");
		await expect(within(scope).getByRole("button", { name: /Recorded/u })).toHaveTextContent(
			"1 Sep – 10 Sep 2026",
		);

		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(args.onReset).toHaveBeenCalledTimes(1);
		await expect(
			within(scope).getByRole("combobox", { name: /^Developer(?::|$)/u }),
		).toHaveTextContent(STORY_PEOPLE.options[0]?.label ?? "");

		await userEvent.click(within(scope).getByRole("button", { name: "Clear scope" }));
		await expect(args.onClearScope).toHaveBeenCalledTimes(1);
		await expect(
			within(scope).queryByRole("button", { name: "Clear scope" }),
		).not.toBeInTheDocument();
	},
};

export const AGroupNarrowsThePractices: Story = {
	args: { search: { ...UNFILTERED, groupSlug: [practiceGroups[0]?.slug ?? ""] } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Practice" }));
		const listbox = await screen.findByRole("listbox", { name: "Practice options" });
		const offered = await within(listbox).findAllByRole("option");
		const inGroup = workspacePractices.filter(
			(practice) => practice.groupSlug === practiceGroups[0]?.slug,
		);
		await expect(offered).toHaveLength(inGroup.length);
	},
};

export const TurnedOffPracticesHidden: Story = {
	args: {
		practices: {
			...STORY_PRACTICES,
			options: STORY_PRACTICES.options.map((option) =>
				option.value === "thin-controllers" ? { ...option, turnedOff: true } : option,
			),
		},
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Practice" }));
		const options = await screen.findByRole("listbox", { name: "Practice options" });
		await expect(within(options).queryByRole("option", { name: /Thin controllers/u })).toBeNull();
		const show = await screen.findByRole("switch", { name: /^Show turned-off practices/u });
		await userEvent.click(show);
		await within(options).findByRole("option", { name: /Thin controllers/u });
	},
};

// An unavailable selected practice must remain visible so the reader can clear it.
export const ChosenTurnedOffPracticeStays: Story = {
	args: {
		search: { ...UNFILTERED, practiceSlug: ["thin-controllers"] },
		practices: {
			...STORY_PRACTICES,
			options: STORY_PRACTICES.options.map((option) =>
				option.value === "thin-controllers" ? { ...option, turnedOff: true } : option,
			),
		},
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: /^Practice/u }));
		const options = await screen.findByRole("listbox", { name: "Practice options" });
		await within(options).findByRole("option", { name: /Thin controllers/u });
	},
};
