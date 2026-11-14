import type { Meta, StoryObj } from "@storybook/react-vite";
import { type ComponentProps, useState } from "react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { CatalogGroup, CatalogPractice } from "@/api/types.gen";
import { mockIssueReviewFields } from "@/mocks/fixtures/practice";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import type { CatalogSearch } from "./catalog-search";
import { CatalogPage } from "./CatalogPage";
import { catalogGroup, catalogPractice, catalogPracticeDefinition } from "./fixtures";

const reviewReadyGroup = catalogGroup();

const excludedGroup = catalogGroup({
	slug: "not-offered",
	position: 2,
	name: "Legacy conventions",
	description: null,
	icon: null,
	color: null,
	excluded: true,
	offered: false,
});

const smallFocusedPrs = catalogPractice({
	slug: "small-focused-prs",
	groupSlug: "review-ready-work",
	definition: catalogPracticeDefinition({ name: "Keep a change to one concern" }),
});

const explainsTheChange = catalogPractice({
	slug: "explains-the-change",
	position: 1,
	groupSlug: "review-ready-work",
	definition: catalogPracticeDefinition({ name: "Say what changed and why" }),
	state: "UPDATE_WAITING",
	changeKind: "REVIEW",
});

const groups: CatalogGroup[] = [
	reviewReadyGroup,
	catalogGroup({
		slug: "house-rules",
		position: 1,
		name: "Team conventions",
		description: null,
		icon: "Scale",
		color: "amber",
		state: "YOURS",
	}),
	excludedGroup,
];

const practices: CatalogPractice[] = [
	smallFocusedPrs,
	explainsTheChange,
	catalogPractice({
		slug: "reworded-upstream",
		position: 2,
		groupSlug: "review-ready-work",
		definition: catalogPracticeDefinition({ name: "Respond to each review comment" }),
		state: "UPDATE_WAITING",
		changeKind: "WORDING",
	}),
	catalogPractice({
		slug: "our-release-notes",
		groupSlug: "house-rules",
		definition: catalogPracticeDefinition({
			name: "Write the release note with the change",
			reviewedBy: { by: "NOBODY", humanReason: null, limits: [] },
		}),
		state: "YOURS",
	}),
	catalogPractice({
		slug: "link-the-issue",
		kind: "scm.issue",
		definition: catalogPracticeDefinition({
			name: "Link the issue the change closes",
			...mockIssueReviewFields,
			reviewedBy: {
				by: "PEOPLE",
				humanReason: {
					code: "NEEDS_PRODUCT_CONTEXT",
					description: "Only the team knows which issue a change closes.",
				},
				limits: [],
			},
		}),
		state: "NO_LONGER_SHIPPED",
	}),
	catalogPractice({
		slug: "orphaned-practice",
		groupSlug: "a-group-hephaestus-stopped-shipping",
		definition: catalogPracticeDefinition({ name: "Outlived the group it was filed under" }),
		state: "NO_LONGER_SHIPPED",
		excluded: true,
		offered: false,
	}),
];

const meta = {
	component: CatalogPage,
	parameters: { layout: "fullscreen", chromatic: { viewports: [1440] } },
	decorators: [withStandardPage],
	args: {
		groups,
		practices,
		search: {},
		onSearchChange: fn(),
		onPracticeStatusChange: fn(),
		onGroupStatusChange: fn(),
		onReorderGroups: fn(),
		onPlacePractice: fn(),
		onResetOrder: fn(),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof CatalogPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Everything: Story = {};

/** A screen reader hears the save: the row's live region is there before it and then names the group. */
export const SavingIsAnnounced: Story = {
	render: (args) => <GroupSave {...args} />,
	play: async ({ canvas }) => {
		const regions = canvas.getAllByRole("status");
		await expect(regions.map((region) => region.textContent)).not.toContain(
			"Saving Legacy conventions…",
		);

		await userEvent.click(
			canvas.getByRole("switch", { name: "Include Legacy conventions for workspaces" }),
		);

		const announced = await waitFor(() => {
			const region = canvas
				.getAllByRole("status")
				.find((candidate) => candidate.textContent === "Saving Legacy conventions…");
			if (region === undefined) {
				throw new Error("No row announces the save yet");
			}
			return region;
		});
		await expect(regions).toContain(announced);
	},
};

export const ReviewsByPeopleAndNobodyAreVisible: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Guidance only")).toBeVisible();
		await expect(canvas.getByText("Reviewed by people")).toBeVisible();
	},
};

export const PendingChangesAreSummarized: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("3 Hephaestus changes need review")).toBeVisible();
		await expect(canvas.getByText("1 update would change review rules")).toBeVisible();
		await expect(canvas.getByText("1 update would change wording or guidance")).toBeVisible();
		await expect(canvas.getByText("1 entry is no longer in Hephaestus defaults")).toBeVisible();
	},
};

export const RestoringTheHephaestusOrder: Story = {
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

/** Marks the group a switch saves as pending, as the route does while the write runs. */
function GroupSave(props: ComponentProps<typeof CatalogPage>) {
	const [pendingGroupSlugs, setPendingGroupSlugs] = useState<ReadonlySet<string>>(new Set());
	return (
		<CatalogPage
			{...props}
			pendingGroupSlugs={pendingGroupSlugs}
			onGroupStatusChange={(group, offered) => {
				props.onGroupStatusChange(group, offered);
				setPendingGroupSlugs(new Set([group.slug]));
			}}
		/>
	);
}

function FilterTransition(props: ComponentProps<typeof CatalogPage>) {
	const [search, setSearch] = useState<CatalogSearch>({});
	return <CatalogPage {...props} search={search} onSearchChange={setSearch} />;
}

export const FilteringOpensMatchingGroups: Story = {
	render: (args) => <FilterTransition {...args} />,
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		const group = canvas.getByRole("button", { name: /^Packaging work for review 3$/u });
		await userEvent.click(group);
		await userEvent.click(canvas.getByRole("combobox", { name: "Filter by kind of work" }));
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
		practices: practices.map((practice) => ({
			...practice,
			state: "FROM_HEPHAESTUS",
			changeKind: "NONE",
			excluded: false,
		})),
		groups: groups.map((group) => ({
			...group,
			state: "FROM_HEPHAESTUS",
			changeKind: "NONE",
			excluded: false,
		})),
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/Hephaestus changes? needs? review/u)).not.toBeInTheDocument();
	},
};

export const ExcludingAGroupListsItsPractices: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		await userEvent.click(
			await canvas.findByRole("switch", {
				name: "Include Packaging work for review for workspaces",
			}),
		);
		const dialog = await screen.findByRole("alertdialog");
		await within(dialog).findByText(/also excludes 3 included practices/u);
		await within(dialog).findByText("Say what changed and why");
	},
};

export const PracticeInsideExcludedGroup: Story = {
	args: {
		groups: [excludedGroup],
		practices: [
			{ ...smallFocusedPrs, groupSlug: excludedGroup.slug, offered: false },
			{
				...explainsTheChange,
				groupSlug: excludedGroup.slug,
				excluded: true,
				offered: false,
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
			name: "Say what changed and why is excluded from workspaces",
		});
		await expect(directlyExcludedSwitch).not.toBeChecked();
		await expect(directlyExcludedSwitch).toHaveAttribute("aria-disabled", "true");
	},
};

export const ExcludingAGroupCountsOnlyIncludedPractices: Story = {
	args: {
		groups: [reviewReadyGroup],
		practices: [smallFocusedPrs, { ...explainsTheChange, excluded: true, offered: false }],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		await userEvent.click(
			await canvas.findByRole("switch", {
				name: "Include Packaging work for review for workspaces",
			}),
		);
		const dialog = await screen.findByRole("alertdialog");
		await within(dialog).findByText(/also excludes 1 included practice/u);
		await within(dialog).findByText("Keep a change to one concern");
		await expect(within(dialog).queryByText("Say what changed and why")).not.toBeInTheDocument();
	},
};

export const ExcludingAGroupDoesNotRecountExcludedPractices: Story = {
	args: {
		groups: [reviewReadyGroup],
		practices: [{ ...smallFocusedPrs, excluded: true, offered: false }],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		await userEvent.click(
			await canvas.findByRole("switch", {
				name: "Include Packaging work for review for workspaces",
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
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The catalog is empty")).toBeVisible();
	},
};
