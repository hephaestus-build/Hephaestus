import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { AUTONOMY_DEFS } from "@/components/practice-vocabulary/autonomy-defs";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPracticeDefinitionOptions,
} from "@/mocks/fixtures/practice";
import { StatefulPatch } from "@/stories/stateful";
import { expectNoPageOverflow } from "@/test/reflow";

import { PracticeCatalog } from "./PracticeCatalog";
import {
	chosenAutonomy,
	groupAutonomy,
	inheritedAutonomy,
	mockGroups,
	mockPracticeLongText,
	mockPractices,
	mockUnassignedPractice,
} from "./story-mock-data";

const [reviewReadyGroup, issueAuthoringGroup] = mockGroups;
const [prDescriptionPractice, reviewThoroughnessPractice, testCoveragePractice] = mockPractices;
if (
	!reviewReadyGroup ||
	!issueAuthoringGroup ||
	!prDescriptionPractice ||
	!reviewThoroughnessPractice ||
	!testCoveragePractice
) {
	throw new Error("The shared practice fixtures no longer cover two groups and three practices");
}

const longNamedGroup = {
	...reviewReadyGroup,
	name: "Packaging work so reviewers can understand its purpose without reconstructing context",
	visibleInPracticeDashboards: false,
};

const groups = [longNamedGroup, issueAuthoringGroup];

const longTextPractice = {
	...mockPracticeLongText,
	groupSlug: longNamedGroup.slug,
	precomputeScript: "export default {};",
};

const issuePractice = {
	...mockUnassignedPractice,
	artifactKind: "scm.issue" as const,
};

const practices = [
	longTextPractice,
	{
		...testCoveragePractice,
		groupSlug: longNamedGroup.slug,
		displayOrder: 1,
	},
	issuePractice,
];

// The size a workspace installs the shipped catalogue at.
const scaleGroups = [
	"Submitting review-ready work",
	"Writing issues a maintainer can act on",
	"Reviewing other people's work",
	"Testing what changed",
	"Documentation",
	"Keeping the build green",
	"Talking about work in the open",
	"Dependencies and supply chain",
].map((name, index) => ({
	...reviewReadyGroup,
	id: 100 + index,
	slug: `scale-group-${index}`,
	name,
	displayOrder: index,
}));

const scalePractices = scaleGroups.flatMap((group, groupIndex) =>
	[
		"states the motivation",
		"links the issue it closes",
		"lists the steps a reviewer ran",
		"keeps the change reviewable in one sitting",
	].map((suffix, practiceIndex) => ({
		...prDescriptionPractice,
		id: 1000 + groupIndex * 10 + practiceIndex,
		slug: `${group.slug}-${practiceIndex}`,
		name: `${group.name}: ${suffix}`,
		groupSlug: group.slug,
		displayOrder: practiceIndex,
	})),
);

const idlePending = {
	groupSlugs: new Set<string>(),
	practiceSlugs: new Set<string>(),
	groupStructure: false,
	blockedMoveDestinationSlugs: new Set<string>(),
	blockedPracticeOrderBuckets: new Set<string>(),
	creatingGroup: false,
};

const meta = {
	title: "Workspace admin/Practices/Practice setup",
	component: PracticeCatalog,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 1440] },
		viewport: { defaultViewport: "reflow" },
	},
	args: {
		workspaceSlug: "demo",
		groups,
		practices,
		definitionOptions: mockPracticeDefinitionOptions,
		pending: idlePending,
		focusFilter: "ALL",
		onFocusFilterChange: fn(),
		onCreateGroup: fn(async () => true),
		onUpdateGroup: fn(async () => true),
		onSetGroupDashboardVisibility: fn(),
		onDeleteGroup: fn(),
		onReorderGroups: fn(),
		onSetGroupVisual: fn(),
		onDeletePractice: fn(),
		onPlacePractice: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-5xl">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
	render: (args) => (
		<StatefulPatch initial={{ focusFilter: args.focusFilter, groups: args.groups }}>
			{(view, patch) => (
				<PracticeCatalog
					{...args}
					focusFilter={view.focusFilter}
					groups={view.groups}
					onFocusFilterChange={(focusFilter) => {
						args.onFocusFilterChange(focusFilter);
						patch({ focusFilter });
					}}
					onSetGroupVisual={(slug, visual) => {
						args.onSetGroupVisual(slug, visual);
						patch({
							groups: view.groups.map((group) =>
								group.slug === slug ? { ...group, ...visual } : group,
							),
						});
					}}
					onSetGroupDashboardVisibility={(slug, visibleInPracticeDashboards) => {
						args.onSetGroupDashboardVisibility(slug, visibleInPracticeDashboards);
						patch({
							groups: view.groups.map((group) =>
								group.slug === slug ? { ...group, visibleInPracticeDashboards } : group,
							),
						});
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof PracticeCatalog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Populated: Story = {
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const WithInstanceCatalog: Story = {
	args: {
		library: {
			open: true,
			onOpenChange: fn(),
			state: {
				status: "ready",
				practices: [
					{
						slug: "explain-change",
						name: "Explain what changed and why",
						artifactKind: "scm.pull_request",
						whyItMatters:
							"A reviewer who has to reconstruct intent from the diff reviews the code and not the decision.",
						groupSlug: "review-ready-work",
						groupName: "Review-ready work",
						availability: "AVAILABLE",
						automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
					},
					{
						slug: longTextPractice.slug,
						name: longTextPractice.name,
						artifactKind: longTextPractice.artifactKind,
						groupSlug: longTextPractice.groupSlug,
						groupName: longNamedGroup.name,
						availability: "ADOPTED",
						automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
					},
				],
			},
		},
	},
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "Instance catalog" });
		await expect(
			canvas.getAllByRole("link", { name: /Explain what changed and why/u }),
		).toHaveLength(1);
	},
};

/** The library is a section of this page, so its failure must not take the tree down with it. */
export const InstanceCatalogFailed: Story = {
	args: {
		library: {
			open: true,
			onOpenChange: fn(),
			state: {
				status: "error",
				// 5xx is the classifier's retryable branch; a bare Error renders no Retry button.
				error: { status: 503, detail: "Catalog service unavailable" },
				onRetry: fn(),
			},
		},
	},
	play: async ({ args, canvas, userEvent }) => {
		// The workspace's own practices are still readable beside the failed library.
		await expect(canvas.getByRole("link", { name: longTextPractice.name })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		const state = args.library?.state;
		await expect(state?.status === "error" && state.onRetry).toHaveBeenCalledOnce();
	},
};

export const InstanceCatalogLoading: Story = {
	args: {
		library: { open: true, onOpenChange: fn(), state: { status: "loading" } },
	},
	play: async ({ canvas }) => {
		const library = within(canvas.getByRole("region", { name: "Instance catalog" }));
		await expect(library.queryByRole("status")).not.toBeInTheDocument();
		await expect(
			canvas
				.getByRole("region", { name: "Instance catalog" })
				.querySelectorAll('[data-slot="skeleton"]').length,
		).toBeGreaterThan(0);
	},
};

export const FilteredToNothing: Story = {
	args: { focusFilter: "docs.document" },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("No practices match this filter")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Clear the filter" }));
		await expect(args.onFocusFilterChange).toHaveBeenCalledWith("ALL");
	},
};

export const AtScale: Story = {
	args: { groups: scaleGroups, practices: scalePractices },
	parameters: { chromatic: { viewports: [320, 1440] } },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("button", { name: /^Reorder /u })).toHaveLength(40);
		await expectNoPageOverflow();
	},
};

export const PracticeNameOpensTheDetailLevel: Story = {
	args: {
		practices: [
			{ ...prDescriptionPractice, groupSlug: longNamedGroup.slug, displayOrder: 0 },
			{ ...mockUnassignedPractice, groupSlug: longNamedGroup.slug, displayOrder: 1 },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: prDescriptionPractice.name });
		await expect(
			new URL(link.getAttribute("href") ?? "", "https://example.test").searchParams.get("detail"),
		).toBe('["practice:pr-description-quality"]');
	},
};

export const Filtered: Story = {
	args: { focusFilter: "scm.issue" },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Clear the filter to reorder practices.")).toBeVisible();
		// The row the filter keeps loses its handle; the group's own handle stays, because only entry
		// handles are gated on the filter.
		await expect(
			canvas.queryByRole("button", { name: `Reorder ${issuePractice.name}` }),
		).not.toBeInTheDocument();
		await expect(
			canvas.getByRole("button", { name: `Reorder ${longNamedGroup.name}` }),
		).toBeVisible();
	},
};

export const MoveToUnassigned: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: `More actions for ${mockPracticeLongText.name}` }),
		);
		await userEvent.click(await screen.findByRole("menuitemradio", { name: "Unassigned" }));
		await expect(args.onPlacePractice).toHaveBeenCalledWith(mockPracticeLongText.slug, null, 1);
	},
};

export const MoveWithoutDragging: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: `More actions for ${mockPracticeLongText.name}` }),
		);
		await userEvent.click(await screen.findByRole("menuitem", { name: "Move down" }));
		await expect(args.onPlacePractice).toHaveBeenCalledWith(
			mockPracticeLongText.slug,
			longNamedGroup.slug,
			1,
		);
	},
};

export const MoveGroupWithoutDragging: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: `More actions for ${longNamedGroup.name}` }),
		);
		await userEvent.click(await screen.findByRole("menuitem", { name: "Move down" }));
		await expect(args.onReorderGroups).toHaveBeenCalledWith([
			issueAuthoringGroup.slug,
			longNamedGroup.slug,
		]);
	},
};

export const KeyboardReordering: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas }) => {
		const handle = canvas.getByRole("button", {
			name: `Reorder ${mockPracticeLongText.name}`,
		});
		handle.focus();
		handle.dispatchEvent(new KeyboardEvent("keydown", { key: " ", code: "Space", bubbles: true }));
		await screen.findByText(`Picked up ${mockPracticeLongText.name}.`);
		handle.dispatchEvent(
			new KeyboardEvent("keydown", { key: "ArrowDown", code: "ArrowDown", bubbles: true }),
		);
		await screen.findByText(new RegExp(`Moving ${mockPracticeLongText.name}`, "u"));
		handle.dispatchEvent(new KeyboardEvent("keydown", { key: " ", code: "Space", bubbles: true }));
		await expect(args.onPlacePractice).toHaveBeenCalledWith(
			mockPracticeLongText.slug,
			longNamedGroup.slug,
			1,
		);
	},
};

export const Reordering: Story = {
	args: {
		pending: {
			...idlePending,
			blockedPracticeOrderBuckets: new Set([longNamedGroup.slug]),
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", {
				name: `Reorder ${mockPracticeLongText.name}`,
			}),
		).toBeDisabled();
		await expect(
			canvas.getByRole("button", {
				name: `Reorder ${mockUnassignedPractice.name}`,
			}),
		).toBeEnabled();
	},
};

export const DeletingGroup: Story = {
	args: {
		pending: {
			...idlePending,
			groupSlugs: new Set([longNamedGroup.slug]),
			groupStructure: true,
			blockedMoveDestinationSlugs: new Set([longNamedGroup.slug]),
			blockedPracticeOrderBuckets: new Set([longNamedGroup.slug, "__unassigned__"]),
		},
	},
	play: async ({ canvas, userEvent }) => {
		const actions = canvas.getByRole("button", {
			name: `More actions for ${mockUnassignedPractice.name}`,
		});
		await userEvent.click(actions);
		await expect(
			await screen.findByRole("menuitemradio", { name: issueAuthoringGroup.name }),
		).toBeEnabled();
		await expect(screen.getByRole("menuitemradio", { name: longNamedGroup.name })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

export const Empty: Story = {
	args: {
		groups: [],
		practices: [],
		library: { open: false, onOpenChange: fn(), state: { status: "loading" } },
	},
	parameters: { chromatic: { viewports: [1440] } },
};

export const EmptyDestinations: Story = {
	args: {
		groups: mockGroups,
		practices: [],
	},
	play: async ({ canvas }) => {
		for (const group of mockGroups) {
			const groupSection = canvas.getByText(group.name).closest('[data-slot="accordion-item"]');
			if (!(groupSection instanceof HTMLElement)) {
				throw new Error(`Group ${group.name} not rendered`);
			}
			await expect(within(groupSection).getByText("No practices here.")).toBeVisible();
		}
		await expect(canvas.getByText("Nothing unassigned.")).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const CrossGroupDrag: Story = {
	args: {
		groups: mockGroups,
		practices: [{ ...prDescriptionPractice, groupSlug: reviewReadyGroup.slug }],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const handle = canvas.getByRole("button", { name: `Reorder ${prDescriptionPractice.name}` });
		const destinationGroup = canvas
			.getByText(issueAuthoringGroup.name)
			.closest('[data-slot="accordion-item"]');
		if (!(destinationGroup instanceof HTMLElement)) {
			throw new Error("Destination group not rendered");
		}
		const destination = within(destinationGroup).getByText("No practices here.");
		const sourceRow = handle.closest('[data-slot="item"]');
		if (!(sourceRow instanceof HTMLElement)) {
			throw new Error("Practice row not rendered");
		}
		const start = handle.getBoundingClientRect();
		const end = destination.getBoundingClientRect();

		await userEvent.pointer([
			{
				target: handle,
				coords: { x: start.left + start.width / 2, y: start.top + start.height / 2 },
				keys: "[MouseLeft>]",
			},
			{
				target: handle,
				coords: { x: start.left + start.width / 2, y: start.top + start.height / 2 + 10 },
			},
		]);
		await expect(handle).toBeVisible();
		const preview = await waitFor(async () => {
			const rows = screen
				.getAllByText(prDescriptionPractice.name)
				.map((name) => name.closest('[data-slot="item"]'))
				.filter((row): row is HTMLElement => row instanceof HTMLElement && row !== sourceRow);
			await expect(rows).toHaveLength(1);
			const [previewRow] = rows;
			if (!previewRow) {
				throw new Error("The drag preview row disappeared");
			}
			return previewRow;
		});
		await expect(
			Math.abs(preview.getBoundingClientRect().width - sourceRow.getBoundingClientRect().width),
		).toBeLessThan(1);
		await userEvent.pointer([
			{
				target: destination,
				coords: { x: end.left + end.width / 2, y: end.top + end.height / 2 },
			},
			{ target: destination, keys: "[/MouseLeft]" },
		]);

		await expect(args.onPlacePractice).toHaveBeenCalledWith(
			prDescriptionPractice.slug,
			issueAuthoringGroup.slug,
			0,
		);
	},
};

export const BetweenRowsDrag: Story = {
	args: {
		groups: mockGroups,
		practices: [
			{ ...prDescriptionPractice, groupSlug: reviewReadyGroup.slug },
			{ ...reviewThoroughnessPractice, groupSlug: issueAuthoringGroup.slug, displayOrder: 0 },
			{ ...testCoveragePractice, groupSlug: issueAuthoringGroup.slug, displayOrder: 1 },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const source = prDescriptionPractice;
		const anchor = testCoveragePractice;
		const handle = canvas.getByRole("button", { name: `Reorder ${source.name}` });
		const anchorRow = canvas.getByText(anchor.name).closest<HTMLElement>('[data-slot="item"]');
		if (!anchorRow) {
			throw new Error("Destination practice row not rendered");
		}
		const start = handle.getBoundingClientRect();
		const end = anchorRow.getBoundingClientRect();

		await userEvent.pointer([
			{
				target: handle,
				coords: { x: start.left + start.width / 2, y: start.top + start.height / 2 },
				keys: "[MouseLeft>]",
			},
			{
				target: handle,
				coords: { x: start.left + start.width / 2, y: start.top + start.height / 2 + 10 },
			},
			{
				target: anchorRow,
				coords: { x: end.left + end.width / 2, y: end.top + end.height * 0.25 },
			},
			{ target: anchorRow, keys: "[/MouseLeft]" },
		]);

		await expect(args.onPlacePractice).toHaveBeenCalledWith(
			source.slug,
			issueAuthoringGroup.slug,
			1,
		);
	},
};

export const BlockedDestinationDrag: Story = {
	args: {
		groups: mockGroups,
		practices: [{ ...prDescriptionPractice, groupSlug: undefined }],
		pending: {
			...idlePending,
			blockedMoveDestinationSlugs: new Set([issueAuthoringGroup.slug]),
		},
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const handle = canvas.getByRole("button", { name: `Reorder ${prDescriptionPractice.name}` });
		const destinationGroup = canvas
			.getByText(issueAuthoringGroup.name)
			.closest<HTMLElement>('[data-slot="accordion-item"]');
		if (!destinationGroup) {
			throw new Error("Blocked destination group not rendered");
		}
		const destination = within(destinationGroup).getByText("No practices here.");
		const start = handle.getBoundingClientRect();
		const end = destination.getBoundingClientRect();

		await userEvent.pointer([
			{
				target: handle,
				coords: { x: start.left + start.width / 2, y: start.top + start.height / 2 },
				keys: "[MouseLeft>]",
			},
			{
				target: handle,
				coords: { x: start.left + start.width / 2, y: start.top + start.height / 2 + 10 },
			},
			{
				target: destination,
				coords: { x: end.left + end.width / 2, y: end.top + end.height / 2 },
			},
			{ target: destination, keys: "[/MouseLeft]" },
		]);

		await expect(args.onPlacePractice).not.toHaveBeenCalled();
	},
};

export const AutonomyLevels: Story = {
	args: {
		groups: mockGroups,
		practices: [
			{
				...prDescriptionPractice,
				slug: "from-workspace",
				name: "Nobody has touched this one",
				autonomy: inheritedAutonomy("AUTOMATIC"),
			},
			{
				...prDescriptionPractice,
				slug: "from-group",
				name: "Its group decided",
				autonomy: groupAutonomy("HUMAN_APPROVAL"),
			},
			{
				...prDescriptionPractice,
				slug: "held",
				name: "Singled out",
				autonomy: chosenAutonomy("OFF"),
			},
		].map((practice) => ({ ...practice, groupSlug: reviewReadyGroup.slug })),
	},
	play: async ({ canvas }) => {
		// Scoped row by row: asserting that all three sentences exist somewhere on the page would pass
		// just as well if every row carried the same one, which is the mix-up this story is about.
		const expected = [
			["Nobody has touched this one", "Send automatically", "Follows the workspace default"],
			["Its group decided", "Review before sending", `Follows ${reviewReadyGroup.name}`],
			["Singled out", "Off", "Set for this practice"],
		] as const;

		for (const [name, autonomy, decidedBy] of expected) {
			const listitem = canvas.getByText(name).closest('[role="listitem"]');
			if (!(listitem instanceof HTMLElement)) {
				throw new Error(`No row for ${name}`);
			}
			const row = within(listitem);
			await expect(row.getByText(autonomy).closest('[data-slot="badge"]')).toBeVisible();
			await expect(row.getByText(decidedBy)).toBeVisible();
		}
		await expectNoPageOverflow();
	},
};

export const AutonomyIsReadOnlyHere: Story = {
	args: {
		groups: mockGroups,
		practices: [
			{
				...prDescriptionPractice,
				slug: "held",
				name: "Set here",
				autonomy: chosenAutonomy("HUMAN_APPROVAL"),
			},
		].map((practice) => ({ ...practice, groupSlug: reviewReadyGroup.slug })),
	},
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "desktop" },
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "More actions for Set here" }));
		const menu = within(await screen.findByRole("menu"));
		await expect(menu.getByRole("menuitem", { name: "Change on Review" })).toHaveAttribute(
			"href",
			"/w/demo/admin/practices/review",
		);
		for (const { label } of Object.values(AUTONOMY_DEFS)) {
			await expect(menu.queryByRole("menuitemradio", { name: label })).not.toBeInTheDocument();
			await expect(menu.queryByRole("menuitem", { name: label })).not.toBeInTheDocument();
		}
	},
};
