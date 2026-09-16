import type { Meta, StoryObj } from "@storybook/react";

import { SelectGroupStep } from "./SelectGroupStep";
import { makeGroup, withWizardState } from "./stories-utils";

const hephaestusGroup = makeGroup(1, "Hephaestus", "ls1intum/hephaestus", {
	visibility: "public",
});

const sampleGroups = [
	hephaestusGroup,
	makeGroup(2, "Artemis", "ls1intum/artemis", { visibility: "internal" }),
	makeGroup(3, "Athena", "ls1intum/athena"),
	makeGroup(4, "IRIS", "ls1intum/iris", { visibility: "public" }),
	makeGroup(5, "Design System", "ls1intum/design-system"),
];

/**
 * Group picker step in the GitLab workspace creation wizard.
 * Displays a searchable list of GitLab groups with radio selection,
 * avatars, and visibility badges.
 */
const meta = {
	component: SelectGroupStep,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"Searchable radio-group list of GitLab groups. Uses WizardContext for state and dispatch.",
			},
		},
	},
	decorators: [
		withWizardState({ step: 2, groups: sampleGroups }),
		(Story) => (
			<div className="w-96">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof SelectGroupStep>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default state with multiple groups, none selected.
 */
export const Default: Story = {};

/**
 * A group is pre-selected (highlighted row).
 */
export const WithSelection: Story = {
	decorators: [withWizardState({ step: 2, groups: sampleGroups, selectedGroup: hephaestusGroup })],
};

/**
 * Empty groups list — shows the "no groups found" message.
 */
export const EmptyGroups: Story = {
	decorators: [withWizardState({ step: 2, groups: [] })],
};

/**
 * Single group available.
 */
export const SingleGroup: Story = {
	decorators: [withWizardState({ step: 2, groups: [hephaestusGroup] })],
};

/**
 * Many groups to demonstrate scroll behavior.
 */
export const ManyGroups: Story = {
	decorators: [
		withWizardState({
			step: 2,
			groups: Array.from({ length: 20 }, (_, i) =>
				makeGroup(i + 100, `Group ${i + 1}`, `org/group-${i + 1}`, {
					visibility: i % 3 === 0 ? "public" : i % 3 === 1 ? "internal" : "private",
				}),
			),
		}),
	],
};

/**
 * Groups without avatar URLs — shows fallback initials.
 */
export const NoAvatars: Story = {
	decorators: [
		withWizardState({
			step: 2,
			groups: sampleGroups.map((g) => ({ ...g, avatarUrl: undefined })),
		}),
	],
};
