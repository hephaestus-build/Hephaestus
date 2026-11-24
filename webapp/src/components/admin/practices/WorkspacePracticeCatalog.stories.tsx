import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import type { Practice, PracticeDefinition } from "@/api/types.gen";
import { FEEDBACK_MODE_LABELS } from "@/lib/practice-feedback-mode";
import { expectNoPageOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";

import {
	catalogOrigin,
	chosenFeedbackMode,
	inheritedFeedbackMode,
	mockGroups,
	mockPracticeLongText,
	mockPractices,
	mockUnassignedPractice,
} from "./fixtures";
import { WorkspacePracticeCatalog } from "./WorkspacePracticeCatalog";

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

function withDefinition(practice: Practice, definition: Partial<PracticeDefinition>): Practice {
	return {
		...practice,
		currentVersion: {
			...practice.currentVersion,
			definition: { ...currentDefinition(practice), ...definition },
		},
	};
}

const nameOf = (practice: Practice) => currentDefinition(practice).name;

const longNamedGroup = {
	...reviewReadyGroup,
	name: "Packaging work so reviewers can understand its purpose without reconstructing context",
	shownToPeople: false,
};

const groups = [longNamedGroup, issueAuthoringGroup];

const longTextPractice = {
	...withDefinition(mockPracticeLongText, { script: "export default {};" }),
	groupSlug: longNamedGroup.slug,
};

const issuePractice = { ...mockUnassignedPractice, kind: "scm.issue" };

const practices = [
	longTextPractice,
	{ ...testCoveragePractice, groupSlug: longNamedGroup.slug, position: 1 },
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
	id: String(100 + index),
	slug: `scale-group-${index}`,
	name,
	position: index,
}));

const scalePractices = scaleGroups.flatMap((group, groupIndex) =>
	[
		"states the motivation",
		"links the issue it closes",
		"lists the steps a reviewer ran",
		"keeps the change reviewable in one sitting",
	].map((suffix, practiceIndex) => ({
		...withDefinition(prDescriptionPractice, { name: `${group.name}: ${suffix}` }),
		id: String(1000 + groupIndex * 10 + practiceIndex),
		slug: `${group.slug}-${practiceIndex}`,
		groupSlug: group.slug,
		position: practiceIndex,
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
	component: WorkspacePracticeCatalog,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 1440] },
		viewport: { defaultViewport: "reflow" },
	},
	args: {
		workspaceSlug: "demo",
		groups,
		practices,
		pending: idlePending,
		focusFilter: "ALL",
		onFocusFilterChange: fn(),
		onCreateGroup: fn(async () => true),
		onUpdateGroup: fn(async () => true),
		onSetGroupShownToDevelopers: fn(),
		onDeleteGroup: fn(),
		onResetGroup: fn(),
		onReorderGroups: fn(),
		onSetGroupVisual: fn(),
		onDeletePractice: fn(),
		onDownloadPractice: fn(),
		onMovePractice: fn(),
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
				<WorkspacePracticeCatalog
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
					onSetGroupShownToDevelopers={(slug, shownToPeople) => {
						args.onSetGroupShownToDevelopers(slug, shownToPeople);
						patch({
							groups: view.groups.map((group) =>
								group.slug === slug ? { ...group, shownToPeople } : group,
							),
						});
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof WorkspacePracticeCatalog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Populated: Story = {
	play: async ({ canvas }) => {
		await expectNoPageOverflow();
		// The page's own `<h1>` sits above this component, so a group is a level-2 heading, not the
		// level 3 an accordion defaults to — axe cannot see the skip with no `<h1>` in the story.
		await expect(canvas.getAllByRole("heading", { level: 2 })).toHaveLength(groups.length);
	},
};

/** Each group header is one button, so its provenance badge has to be plain text inside it. */
export const WithCatalogProvenance: Story = {
	args: {
		groups: groups.map((group) => ({ ...group, catalogOrigin: catalogOrigin(group.slug) })),
		practices: practices.map((practice) => ({
			...practice,
			catalogOrigin: catalogOrigin(practice.slug, "UPDATE_WAITING"),
		})),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Same as the catalog")).not.toHaveLength(0);
		await expect(canvas.getAllByText("Catalog update waiting")).not.toHaveLength(0);
	},
};

export const GroupResetToCatalog: Story = {
	args: {
		groups: [
			{ ...longNamedGroup, catalogOrigin: catalogOrigin(longNamedGroup.slug, "EDITED_HERE") },
			{ ...issueAuthoringGroup, catalogOrigin: catalogOrigin(issueAuthoringGroup.slug) },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: `More actions for ${issueAuthoringGroup.name}` }),
		);
		await expect(
			within(await screen.findByRole("menu")).queryByRole("menuitem", { name: "Reset to catalog" }),
		).not.toBeInTheDocument();
		await userEvent.keyboard("{Escape}");
		await userEvent.click(
			canvas.getByRole("button", { name: `More actions for ${longNamedGroup.name}` }),
		);
		await userEvent.click(await screen.findByRole("menuitem", { name: "Reset to catalog" }));
		await expect(args.onResetGroup).toHaveBeenCalledWith(longNamedGroup.slug);
	},
};

export const ReviewedByPeople: Story = {
	args: {
		practices: [
			withDefinition(
				{ ...prDescriptionPractice, groupSlug: longNamedGroup.slug },
				{
					reviewedBy: {
						by: "PEOPLE",
						humanReason: { code: "DESIGN_JUDGEMENT", description: "Design choices need a person." },
						limits: [],
					},
				},
			),
			{ ...testCoveragePractice, groupSlug: longNamedGroup.slug, position: 1 },
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Reviewed by people")).toHaveLength(1);
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
						kind: "scm.pull_request",
						whyItMatters:
							"A reviewer who has to reconstruct intent from the diff reviews the code and not the decision.",
						groupSlug: "review-ready-work",
						groupName: "Review-ready work",
						availability: "AVAILABLE",
						reviewedBy: { by: "HEPHAESTUS", humanReason: null, limits: [] },
					},
					{
						slug: longTextPractice.slug,
						name: nameOf(longTextPractice),
						kind: longTextPractice.kind,
						groupSlug: longTextPractice.groupSlug,
						groupName: longNamedGroup.name,
						availability: "ADOPTED",
						reviewedBy: { by: "HEPHAESTUS", humanReason: null, limits: [] },
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

export const NarrowContainer: Story = {
	args: WithInstanceCatalog.args,
	decorators: [
		(Story) => (
			<div className="w-full max-w-md">
				<Story />
			</div>
		),
	],
	parameters: { chromatic: { viewports: [320, 931, 1440] } },
	play: async ({ canvas }) => {
		const toolbar = canvas.queryByRole("toolbar", { name: "Filter by kind of work" });
		const controls = [
			...(toolbar
				? within(toolbar).getAllByRole("button")
				: [canvas.getByRole("combobox", { name: "Filter by kind of work" })]),
			canvas.getByRole("button", { name: "Show catalog" }),
			canvas.getByRole("button", { name: "Create group" }),
			canvas.getByRole("link", { name: "Create practice" }),
		];
		for (const control of controls) {
			const rect = control.getBoundingClientRect();
			const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
			await expect(hit?.closest("button, a")).toBe(control);
		}
		await expectNoPageOverflow();
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
		await expect(canvas.getByRole("link", { name: nameOf(longTextPractice) })).toBeVisible();
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
			{ ...prDescriptionPractice, groupSlug: longNamedGroup.slug, position: 0 },
			{ ...mockUnassignedPractice, groupSlug: longNamedGroup.slug, position: 1 },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: nameOf(prDescriptionPractice) });
		await expect(
			new URL(link.getAttribute("href") ?? "", "https://example.test").searchParams.get("detail"),
		).toBe("practice:pr-description-quality");
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
			canvas.queryByRole("button", { name: `Reorder ${nameOf(issuePractice)}` }),
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
			canvas.getByRole("button", { name: `More actions for ${nameOf(mockPracticeLongText)}` }),
		);
		await userEvent.click(await screen.findByRole("menuitemradio", { name: "Unassigned" }));
		await expect(args.onMovePractice).toHaveBeenCalledWith(mockPracticeLongText.slug, null, 1);
	},
};

export const DownloadAsFiles: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: `More actions for ${nameOf(mockPracticeLongText)}` }),
		);
		await userEvent.click(await screen.findByRole("menuitem", { name: "Download as files" }));
		await expect(args.onDownloadPractice).toHaveBeenCalledWith(
			expect.objectContaining({ slug: mockPracticeLongText.slug }),
		);
	},
};

export const MoveWithoutDragging: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: `More actions for ${nameOf(mockPracticeLongText)}` }),
		);
		await userEvent.click(await screen.findByRole("menuitem", { name: "Move down" }));
		await expect(args.onMovePractice).toHaveBeenCalledWith(
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

/**
 * Space picks the first row up, Arrow Down moves it past the next row, Space drops it there. The
 * dragged row has a long name and its drag preview has its own height; where the move lands must
 * not depend on either. Each step waits on the exact words a screen reader hears, so the drop waits
 * for the move to reach the second place, and real key presses let dnd-kit's keyboard sensor, which
 * starts listening a task after the pick-up, take every key.
 */
export const KeyboardReordering: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const name = nameOf(mockPracticeLongText);
		const second = `position 2 of 2 in ${longNamedGroup.name}`;
		canvas.getByRole("button", { name: `Reorder ${name}` }).focus();
		await userEvent.keyboard("[Space]");
		await screen.findByText(`Picked up ${name}.`);
		await userEvent.keyboard("[ArrowDown]");
		await screen.findByText(`Moving ${name}, ${second}.`);
		await userEvent.keyboard("[Space]");
		await screen.findByText(`Moved ${name} to ${second}.`);
		await expect(args.onMovePractice).toHaveBeenCalledWith(
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
				name: `Reorder ${nameOf(mockPracticeLongText)}`,
			}),
		).toBeDisabled();
		await expect(
			canvas.getByRole("button", {
				name: `Reorder ${nameOf(mockUnassignedPractice)}`,
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
			name: `More actions for ${nameOf(mockUnassignedPractice)}`,
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
			await expect(within(groupSection).getByText("No practices here")).toBeVisible();
		}
		await expect(canvas.getByText("Nothing unassigned")).toBeVisible();
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
		const handle = canvas.getByRole("button", { name: `Reorder ${nameOf(prDescriptionPractice)}` });
		const destinationGroup = canvas
			.getByText(issueAuthoringGroup.name)
			.closest('[data-slot="accordion-item"]');
		if (!(destinationGroup instanceof HTMLElement)) {
			throw new Error("Destination group not rendered");
		}
		const destination = within(destinationGroup).getByText("No practices here");
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
				.getAllByText(nameOf(prDescriptionPractice))
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

		await expect(args.onMovePractice).toHaveBeenCalledWith(
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
			{ ...reviewThoroughnessPractice, groupSlug: issueAuthoringGroup.slug, position: 0 },
			{ ...testCoveragePractice, groupSlug: issueAuthoringGroup.slug, position: 1 },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const source = prDescriptionPractice;
		const anchor = testCoveragePractice;
		const handle = canvas.getByRole("button", { name: `Reorder ${nameOf(source)}` });
		const anchorRow = canvas.getByText(nameOf(anchor)).closest<HTMLElement>('[data-slot="item"]');
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

		await expect(args.onMovePractice).toHaveBeenCalledWith(
			source.slug,
			issueAuthoringGroup.slug,
			1,
		);
	},
};

export const BlockedDestinationDrag: Story = {
	args: {
		groups: mockGroups,
		practices: [{ ...prDescriptionPractice, groupSlug: null }],
		pending: {
			...idlePending,
			blockedMoveDestinationSlugs: new Set([issueAuthoringGroup.slug]),
		},
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const handle = canvas.getByRole("button", { name: `Reorder ${nameOf(prDescriptionPractice)}` });
		const destinationGroup = canvas
			.getByText(issueAuthoringGroup.name)
			.closest<HTMLElement>('[data-slot="accordion-item"]');
		if (!destinationGroup) {
			throw new Error("Blocked destination group not rendered");
		}
		const destination = within(destinationGroup).getByText("No practices here");
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

		await expect(args.onMovePractice).not.toHaveBeenCalled();
	},
};

/**
 * A practice edited in the practice repository takes its definition and group from its folder there.
 * Its menu keeps those actions in view but unavailable, under the label that says why; its order
 * inside the group stays the workspace's.
 */
export const EditedInRepository: Story = {
	args: {
		practices: practices.map((practice) =>
			practice.slug === longTextPractice.slug
				? { ...practice, editedIn: "REPOSITORY" as const }
				: practice,
		),
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		// Only the practice edited there carries the badge.
		await expect(canvas.getAllByText("Edited in the practice repository")).toHaveLength(1);
		await userEvent.click(
			canvas.getByRole("button", { name: `More actions for ${nameOf(mockPracticeLongText)}` }),
		);
		const menu = within(await screen.findByRole("menu"));
		await expect(menu.getByText("Edited in the practice repository")).toBeVisible();
		await expect(menu.getByRole("menuitem", { name: "Edit practice" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		// Each unavailable item says why on itself: the label sits far from Move to and Delete.
		for (const item of [
			menu.getByRole("menuitem", { name: "Edit practice" }),
			menu.getByRole("menuitem", { name: "Delete practice" }),
			menu.getByRole("menuitemradio", { name: issueAuthoringGroup.name }),
			menu.getByRole("menuitemradio", { name: "Unassigned" }),
		]) {
			await expect(item).toHaveAttribute("aria-disabled", "true");
			await expect(item).toHaveAccessibleDescription("Edited in the practice repository");
		}
		// Download reads the practice and changes nothing, so it stays.
		await expect(menu.getByRole("menuitem", { name: "Download as files" })).not.toHaveAttribute(
			"aria-disabled",
			"true",
		);

		await userEvent.click(menu.getByRole("menuitem", { name: "Move down" }));
		await expect(args.onMovePractice).toHaveBeenCalledTimes(1);
		await expect(args.onMovePractice).toHaveBeenCalledWith(
			mockPracticeLongText.slug,
			longNamedGroup.slug,
			1,
		);
	},
};

export const RepositoryPracticeDragStaysInItsGroup: Story = {
	args: {
		groups: mockGroups,
		practices: [{ ...prDescriptionPractice, groupSlug: null, editedIn: "REPOSITORY" }],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const handle = canvas.getByRole("button", { name: `Reorder ${nameOf(prDescriptionPractice)}` });
		const destinationGroup = canvas
			.getByText(issueAuthoringGroup.name)
			.closest<HTMLElement>('[data-slot="accordion-item"]');
		if (!destinationGroup) {
			throw new Error("Destination group not rendered");
		}
		const destination = within(destinationGroup).getByText("No practices here");
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

		await expect(args.onMovePractice).not.toHaveBeenCalled();
	},
};

export const FeedbackModeLevels: Story = {
	args: {
		groups: mockGroups,
		practices: [
			{
				...withDefinition(prDescriptionPractice, { name: "Nobody has touched this one" }),
				slug: "from-workspace",
				feedbackMode: inheritedFeedbackMode("AUTOMATIC"),
			},
			{
				...withDefinition(prDescriptionPractice, { name: "Its group decided" }),
				slug: "from-group",
				feedbackMode: inheritedFeedbackMode("APPROVE_FIRST", "GROUP"),
			},
			{
				...withDefinition(prDescriptionPractice, { name: "Singled out" }),
				slug: "held",
				feedbackMode: chosenFeedbackMode("OFF"),
			},
		].map((practice) => ({ ...practice, groupSlug: reviewReadyGroup.slug })),
	},
	play: async ({ canvas }) => {
		// Scoped row by row: asserting that all three sentences exist somewhere on the page would pass
		// just as well if every row carried the same one, which is the mix-up this story is about.
		const expected = [
			["Nobody has touched this one", "Automatic", "Follows the workspace default"],
			["Its group decided", "Approve first", `Follows ${reviewReadyGroup.name}`],
			["Singled out", "Off", "Set for this practice"],
		] as const;

		for (const [name, feedbackMode, decidedBy] of expected) {
			const listitem = canvas.getByText(name).closest('[role="listitem"]');
			if (!(listitem instanceof HTMLElement)) {
				throw new Error(`No row for ${name}`);
			}
			const row = within(listitem);
			await expect(row.getByText(feedbackMode).closest('[data-slot="badge"]')).toBeVisible();
			await expect(row.getByText(decidedBy)).toBeVisible();
		}
		await expectNoPageOverflow();
	},
};

export const FeedbackModeIsReadOnlyHere: Story = {
	args: {
		groups: mockGroups,
		practices: [
			{
				...withDefinition(prDescriptionPractice, { name: "Set here" }),
				slug: "held",
				groupSlug: reviewReadyGroup.slug,
				feedbackMode: chosenFeedbackMode("APPROVE_FIRST"),
			},
		],
	},
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "desktop" },
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "More actions for Set here" }));
		const menu = within(await screen.findByRole("menu"));
		await expect(menu.getByRole("menuitem", { name: "Change in review settings" })).toHaveAttribute(
			"href",
			"/w/demo/admin/practices/review",
		);
		for (const label of Object.values(FEEDBACK_MODE_LABELS)) {
			await expect(menu.queryByRole("menuitemradio", { name: label })).not.toBeInTheDocument();
			await expect(menu.queryByRole("menuitem", { name: label })).not.toBeInTheDocument();
		}
	},
};
