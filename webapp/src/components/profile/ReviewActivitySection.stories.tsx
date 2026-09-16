import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { daysBefore } from "@/components/common/story-clock";

import { ReviewActivitySection } from "./ReviewActivitySection";

const reviewActivity = [
	{
		id: 1,
		state: "APPROVED" as const,
		submittedAt: daysBefore(2),
		htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/42",
		pullRequest: {
			id: 101,
			title: "Add new feature to dashboard",
			number: 42,
			state: "OPEN" as const,
			isDraft: false,
			isMerged: false,
			htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/42",
			repository: {
				id: 1,
				name: "Hephaestus",
				nameWithOwner: "ls1intum/Hephaestus",
				htmlUrl: "https://github.com/ls1intum/Hephaestus",
				hiddenFromContributions: false,
			},
		},
		score: 80,
		isDismissed: false,
		codeComments: 3,
	},
	{
		id: 2,
		state: "CHANGES_REQUESTED" as const,
		submittedAt: daysBefore(5),
		htmlUrl: "https://github.com/ls1intum/Artemis/pull/123",
		pullRequest: {
			id: 102,
			title: "Fix authentication bugs",
			number: 123,
			state: "OPEN" as const,
			isDraft: false,
			isMerged: false,
			htmlUrl: "https://github.com/ls1intum/Artemis/pull/123",
			repository: {
				id: 2,
				name: "Artemis",
				nameWithOwner: "ls1intum/Artemis",
				htmlUrl: "https://github.com/ls1intum/Artemis",
				hiddenFromContributions: false,
			},
		},
		score: 65,
		isDismissed: false,
		codeComments: 2,
	},
];

const meta = {
	component: ReviewActivitySection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		reviewActivity,
		isLoading: false,
		personLabel: "Jane Doe",
		currUserIsDashboardUser: true,
		canViewAll: true,
		onViewAll: fn(),
	},
} satisfies Meta<typeof ReviewActivitySection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { name: "Review activity" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "View all review activity" }));
		await expect(args.onViewAll).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = {
	args: { isLoading: true, canViewAll: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("No review activity")).toBeNull();
		await expect(canvas.queryByText("Add new feature to dashboard")).toBeNull();
	},
};

export const Empty: Story = {
	args: { reviewActivity: [], canViewAll: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No review activity that counts yet. Try a wider timeframe."),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "View all review activity" })).toBeNull();
	},
};

export const EmptyForAnotherDeveloper: Story = {
	args: { reviewActivity: [], canViewAll: false, currUserIsDashboardUser: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Jane Doe has no review activity that counts in this timeframe."),
		).toBeVisible();
	},
};
