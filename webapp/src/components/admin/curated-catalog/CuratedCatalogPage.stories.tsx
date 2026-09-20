import type { Meta, StoryObj } from "@storybook/react-vite";
import { type ComponentProps, useState } from "react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { CatalogEntryStatus, CuratedGroup, CuratedPracticeSummary } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import type { CuratedCatalogSearch } from "./curated-catalog-search";
import { CuratedCatalogPage } from "./CuratedCatalogPage";

const status = (overrides: Partial<CatalogEntryStatus> = {}): CatalogEntryStatus => ({
	etag: "tag",
	state: "FROM_HEPHAESTUS",
	changeKind: "NONE",
	offered: true,
	...overrides,
});

const automatedReview: CuratedPracticeSummary["automatedReview"] = {
	mode: "LANGUAGE_MODEL",
	evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
};

const reviewReadyGroup: CuratedGroup = {
	slug: "review-ready-work",
	position: 0,
	definition: {
		name: "Packaging work for review",
		description: "Make a change cheap to review before you ask for one.",
		icon: "Package",
		color: "sky",
	},
	status: status(),
};

const excludedGroup: CuratedGroup = {
	slug: "not-offered",
	position: 2,
	definition: { name: "Legacy conventions" },
	status: status({ offered: false }),
};

const smallFocusedPrs: CuratedPracticeSummary = {
	slug: "small-focused-prs",
	position: 0,
	name: "Keep a change to one concern",
	artifactKind: "scm.pull_request",
	automatedReview,
	groupSlug: "review-ready-work",
	effectivelyOffered: true,
	status: status(),
};

const explainsTheChange: CuratedPracticeSummary = {
	slug: "explains-the-change",
	position: 1,
	name: "Say what changed and why",
	artifactKind: "scm.pull_request",
	automatedReview,
	groupSlug: "review-ready-work",
	effectivelyOffered: true,
	status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }),
};

const groups: CuratedGroup[] = [
	reviewReadyGroup,
	{
		slug: "house-rules",
		position: 1,
		definition: { name: "Team conventions", icon: "Scale", color: "amber" },
		status: status({ state: "YOURS" }),
	},
	excludedGroup,
];

const practices: CuratedPracticeSummary[] = [
	smallFocusedPrs,
	explainsTheChange,
	{
		slug: "reworded-upstream",
		position: 2,
		name: "Respond to each review comment",
		artifactKind: "scm.pull_request",
		automatedReview,
		groupSlug: "review-ready-work",
		effectivelyOffered: true,
		status: status({ state: "UPDATE_WAITING", changeKind: "WORDING" }),
	},
	{
		slug: "our-release-notes",
		position: 0,
		name: "Write the release note with the change",
		artifactKind: "scm.pull_request",
		automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" },
		groupSlug: "house-rules",
		effectivelyOffered: true,
		status: status({ state: "YOURS" }),
	},
	{
		slug: "link-the-issue",
		position: 0,
		name: "Link the issue the change closes",
		artifactKind: "scm.issue",
		automatedReview: {
			mode: "LANGUAGE_MODEL",
			evidenceSufficiency: "DECLARED_EVIDENCE_INSUFFICIENT",
		},
		effectivelyOffered: true,
		status: status({ state: "NO_LONGER_SHIPPED" }),
	},
	{
		slug: "orphaned-practice",
		position: 0,
		name: "Outlived the group it was filed under",
		artifactKind: "scm.pull_request",
		automatedReview,
		groupSlug: "an-group-hephaestus-stopped-shipping",
		effectivelyOffered: false,
		status: status({ state: "NO_LONGER_SHIPPED", offered: false }),
	},
];

const meta = {
	component: CuratedCatalogPage,
	parameters: { layout: "fullscreen", chromatic: { viewports: [1440] } },
	decorators: [withStandardPage],
	args: {
		groups,
		practices,
		summary: {
			total: groups.length + practices.length,
			updatesChangingDetection: 1,
			updatesChangingWordingOnly: 1,
			updatesChangingPresentation: 0,
			editedHere: 0,
			yours: 2,
			notOffered: 1,
			noLongerShipped: 1,
		},
		search: {},
		customOrder: false,
		onSearchChange: fn(),
		onPracticeStatusChange: fn(),
		onGroupStatusChange: fn(),
		onReorderGroups: fn(),
		onPlacePractice: fn(),
		onResetOrder: fn(),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof CuratedCatalogPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Everything: Story = {};

export const MentoringLimitsAreVisible: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Guidance only")).toBeVisible();
		await expect(canvas.getByText("Human review needed")).toBeVisible();
	},
};

export const CustomOrder: Story = {
	args: { customOrder: true },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Use Hephaestus order" }));
		const dialog = await screen.findByRole("alertdialog");
		await expect(dialog).toHaveAccessibleDescription(/Definitions and inclusion will not change/u);
		await userEvent.click(within(dialog).getByRole("button", { name: "Use Hephaestus order" }));
		await expect(args.onResetOrder).toHaveBeenCalledOnce();
	},
};

export const OnlyIncluded: Story = { args: { search: { status: "OFFERED" } } };

export const APracticeWhoseGroupIsGone: Story = {
	play: async ({ args, canvas }) => {
		await canvas.findByText("Outlived the group it was filed under");
		await canvas.findByText("Group no longer exists");
		await expect(
			canvas.getByRole("switch", {
				name: "Outlived the group it was filed under cannot be included until it is moved out of the missing group",
			}),
		).toHaveAttribute("aria-disabled", "true");
		await userEvent.click(
			canvas.getByRole("button", {
				name: "More actions for Outlived the group it was filed under",
			}),
		);
		const disabledMove = await screen.findByRole("menuitem", {
			name: "Move to Unassigned or an included group first",
		});
		await expect(disabledMove).toHaveAttribute("aria-disabled", "true");
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => expect(disabledMove).not.toBeInTheDocument());
		await userEvent.click(
			canvas.getByRole("button", {
				name: "More actions for Outlived the group it was filed under",
			}),
		);
		await userEvent.click(await screen.findByRole("menuitemradio", { name: "Unassigned" }));
		await expect(args.onPlacePractice).toHaveBeenCalledWith("orphaned-practice", null, 1);
	},
};

export const UnavailableMoveDestinationIsNamed: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "More actions for Link the issue the change closes" }),
		);
		await screen.findByRole("menuitemradio", {
			name: "Legacy conventions (excluded)",
		});
	},
};

/** Owns the search term, so filtering is a state transition rather than two separate renders. */
function FilterTransition(props: ComponentProps<typeof CuratedCatalogPage>) {
	const [search, setSearch] = useState<CuratedCatalogSearch>({});
	return <CuratedCatalogPage {...props} search={search} onSearchChange={setSearch} />;
}

export const FilteringOpensMatchingGroups: Story = {
	render: (args) => <FilterTransition {...args} />,
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		const group = canvas.getByRole("button", { name: /^Packaging work for review 3$/u });
		await userEvent.click(group);
		await userEvent.click(canvas.getByRole("combobox", { name: "Filter by work type" }));
		await userEvent.click(await screen.findByRole("option", { name: "Pull or merge requests" }));
		await canvas.findByText("Keep a change to one concern");
		await expect(group).toHaveAttribute("aria-expanded", "true");
		await expect(group).toHaveAttribute("aria-disabled", "true");
	},
};

export const SearchOpensTheGroupHoldingTheMatch: Story = {
	args: { search: { q: "release note" } },
	play: async ({ canvas }) => {
		await canvas.findByText("Write the release note with the change");
	},
};

export const NothingHasBeenChanged: Story = {
	args: {
		practices: practices.map((practice) => ({ ...practice, status: status() })),
		groups: groups.map((group) => ({ ...group, status: status() })),
		summary: {
			total: groups.length + practices.length,
			updatesChangingDetection: 0,
			updatesChangingWordingOnly: 0,
			updatesChangingPresentation: 0,
			editedHere: 0,
			yours: 0,
			notOffered: 0,
			noLongerShipped: 0,
		},
	},
};

export const ExcludingAGroupListsItsPractices: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		await userEvent.click(
			await canvas.findByRole("switch", {
				name: "Offer Packaging work for review to workspaces",
			}),
		);
		const dialog = await screen.findByRole("alertdialog");
		await within(dialog).findByText(/also stops offering 3 currently offered practices/u);
		await within(dialog).findByText("Say what changed and why");
	},
};

export const PracticeInsideExcludedGroup: Story = {
	args: {
		groups: [excludedGroup],
		practices: [
			{
				...smallFocusedPrs,
				groupSlug: excludedGroup.slug,
				effectivelyOffered: false,
			},
			{
				...explainsTheChange,
				groupSlug: excludedGroup.slug,
				effectivelyOffered: false,
				status: status({ offered: false }),
			},
		],
	},
	play: async ({ canvas }) => {
		const inheritedSwitch = await canvas.findByRole("switch", {
			name: "Keep a change to one concern is excluded because its group is excluded",
		});
		await expect(inheritedSwitch).not.toBeChecked();
		await expect(inheritedSwitch).toHaveAttribute("aria-disabled", "true");
		const directlyExcludedSwitch = await canvas.findByRole("switch", {
			name: "Say what changed and why is not offered to workspaces",
		});
		await expect(directlyExcludedSwitch).not.toBeChecked();
		await expect(directlyExcludedSwitch).toHaveAttribute("aria-disabled", "true");
	},
};

export const ExcludingAGroupCountsOnlyIncludedPractices: Story = {
	args: {
		groups: [reviewReadyGroup],
		practices: [
			smallFocusedPrs,
			{
				...explainsTheChange,
				effectivelyOffered: false,
				status: status({ offered: false }),
			},
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		await userEvent.click(
			await canvas.findByRole("switch", {
				name: "Offer Packaging work for review to workspaces",
			}),
		);
		const dialog = await screen.findByRole("alertdialog");
		await within(dialog).findByText(/also stops offering 1 currently offered practice/u);
		await within(dialog).findByText("Keep a change to one concern");
		await expect(within(dialog).queryByText("Say what changed and why")).not.toBeInTheDocument();
	},
};

export const ExcludingAGroupDoesNotRecountExcludedPractices: Story = {
	args: {
		groups: [reviewReadyGroup],
		practices: [
			{
				...smallFocusedPrs,
				groupSlug: reviewReadyGroup.slug,
				effectivelyOffered: false,
				status: status({ offered: false }),
			},
		],
		summary: {
			...meta.args.summary,
			total: 2,
			updatesChangingDetection: 0,
			updatesChangingWordingOnly: 0,
			noLongerShipped: 0,
		},
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		await userEvent.click(
			await canvas.findByRole("switch", {
				name: "Offer Packaging work for review to workspaces",
			}),
		);
		await screen.findByText(/No additional practices are affected/u);
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};

export const Empty: Story = {
	args: {
		groups: [],
		practices: [],
		summary: {
			total: 0,
			updatesChangingDetection: 0,
			updatesChangingWordingOnly: 0,
			updatesChangingPresentation: 0,
			editedHere: 0,
			yours: 0,
			notOffered: 0,
			noLongerShipped: 0,
		},
	},
};
