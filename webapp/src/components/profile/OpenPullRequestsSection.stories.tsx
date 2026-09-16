import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { daysBefore } from "@/components/common/story-clock";

import { OpenPullRequestsSection } from "./OpenPullRequestsSection";

const pullRequests = [
	{
		id: 101,
		number: 42,
		title: "Add new analytics dashboard",
		state: "OPEN" as const,
		isDraft: false,
		isMerged: false,
		commentsCount: 5,
		additions: 250,
		deletions: 30,
		htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/42",
		createdAt: daysBefore(3),
		repository: {
			id: 1,
			name: "Hephaestus",
			nameWithOwner: "ls1intum/Hephaestus",
			htmlUrl: "https://github.com/ls1intum/Hephaestus",
			hiddenFromContributions: false,
		},
		labels: [
			{ id: 1, name: "enhancement", color: "0E8A16" },
			{ id: 2, name: "frontend", color: "FBCA04" },
		],
	},
	{
		id: 102,
		number: 87,
		title: "WIP: Refactor authentication module",
		state: "OPEN" as const,
		isDraft: true,
		isMerged: false,
		commentsCount: 0,
		additions: 320,
		deletions: 280,
		htmlUrl: "https://github.com/ls1intum/Artemis/pull/87",
		createdAt: daysBefore(1),
		repository: {
			id: 2,
			name: "Artemis",
			nameWithOwner: "ls1intum/Artemis",
			htmlUrl: "https://github.com/ls1intum/Artemis",
			hiddenFromContributions: false,
		},
		labels: [
			{ id: 3, name: "refactoring", color: "D93F0B" },
			{ id: 4, name: "security", color: "5319E7" },
		],
	},
];

const meta = {
	component: OpenPullRequestsSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		pullRequests,
		isLoading: false,
		personLabel: "Jane Doe",
		currUserIsDashboardUser: true,
		canViewAll: true,
		onViewAll: fn(),
	},
} satisfies Meta<typeof OpenPullRequestsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { name: "Open pull requests" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "View all pull requests" }));
		await expect(args.onViewAll).toHaveBeenCalledOnce();
	},
};

export const GitLab: Story = {
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Open merge requests" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "View all merge requests" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { isLoading: true, canViewAll: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("No open pull requests")).toBeNull();
		await expect(canvas.queryByText("Add new analytics dashboard")).toBeNull();
	},
};

export const Empty: Story = {
	args: { pullRequests: [], canViewAll: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Pull Requests you create will appear here.")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "View all pull requests" })).toBeNull();
	},
};

export const EmptyForAnotherDeveloper: Story = {
	args: { pullRequests: [], canViewAll: false, currUserIsDashboardUser: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Jane Doe doesn't have any open pull requests.")).toBeVisible();
	},
};
