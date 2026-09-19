import type { Meta, StoryObj } from "@storybook/react";
import { expect, screen, userEvent } from "storybook/test";

import type { PullRequestInfo } from "@/api/types.gen";
import { withProvider } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";

import { ReviewsPopover } from "./ReviewsPopover";

const mockPullRequests = [
	{
		id: 1,
		number: 101,
		title: "Fix login bug",
		state: "CLOSED",
		isDraft: false,
		isMerged: true,
		commentsCount: 3,
		additions: 50,
		deletions: 10,
		htmlUrl: "https://github.com/org/repo/pull/101",
		repository: {
			id: 1,
			name: "Hephaestus",
			nameWithOwner: "org/repo",
			htmlUrl: "https://github.com/org/repo",
			hiddenFromContributions: false,
		},
	},
	{
		id: 2,
		number: 102,
		title: "Update documentation",
		state: "CLOSED",
		isDraft: false,
		isMerged: true,
		commentsCount: 1,
		additions: 120,
		deletions: 5,
		htmlUrl: "https://github.com/org/repo/pull/102",
		repository: {
			id: 2,
			name: "Artemis",
			nameWithOwner: "org/repo-2",
			htmlUrl: "https://github.com/org/repo-2",
			hiddenFromContributions: false,
		},
	},
] satisfies PullRequestInfo[];

const meta = {
	component: ReviewsPopover,
	tags: ["autodocs"],
	argTypes: {
		highlight: { control: "boolean" },
	},
	parameters: {
		layout: "centered",
	},
	args: { providerType: "GITHUB" },
} satisfies Meta<typeof ReviewsPopover>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithReviews: Story = {
	args: {
		reviewedPullRequests: mockPullRequests,
		highlight: false,
	},
};

export const Highlighted: Story = {
	args: {
		reviewedPullRequests: mockPullRequests,
		highlight: true,
	},
};

// --- Alternate provider variants ---

const mockMergeRequests: PullRequestInfo[] = [
	{
		id: 1,
		number: 101,
		title: "Fix login bug",
		state: "CLOSED",
		isDraft: false,
		isMerged: true,
		commentsCount: 3,
		additions: 50,
		deletions: 10,
		htmlUrl: "https://gitlab.com/org/repo/-/merge_requests/101",
		repository: {
			id: 1,
			name: "Hephaestus",
			nameWithOwner: "org/repo",
			htmlUrl: "https://gitlab.com/org/repo",
			hiddenFromContributions: false,
		},
	},
	{
		id: 2,
		number: 102,
		title: "Update documentation",
		state: "CLOSED",
		isDraft: false,
		isMerged: true,
		commentsCount: 1,
		additions: 120,
		deletions: 5,
		htmlUrl: "https://gitlab.com/org/repo/-/merge_requests/102",
		repository: {
			id: 2,
			name: "Artemis",
			nameWithOwner: "org/repo-2",
			htmlUrl: "https://gitlab.com/org/repo-2",
			hiddenFromContributions: false,
		},
	},
];

/**
 * Alternate provider — uses merge request icons and terminology.
 */
export const WithReviewsMergeRequest: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		reviewedPullRequests: mockMergeRequests,
		highlight: false,
		providerType: "GITLAB",
	},
};

/**
 * Alternate provider highlighted variant.
 */
export const HighlightedMergeRequest: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		reviewedPullRequests: mockMergeRequests,
		highlight: true,
		providerType: "GITLAB",
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Show 2 reviewed/u }));
		await expectSettledVisible(await screen.findByRole("dialog", { name: "Reviewed MRs" }));
		await expect(
			screen.getByRole("button", { name: "Copy links to reviewed merge requests" }),
		).toBeVisible();
	},
};

export const WrappedRepositoryNames: Story = {
	args: {
		reviewedPullRequests: mockPullRequests.map((pullRequest) => ({
			...pullRequest,
			repository: {
				...pullRequest.repository,
				name: "A repository with a long name that wraps onto several lines",
			},
		})),
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Show 2 reviewed/u }));
		await expectSettledVisible(await screen.findByRole("dialog", { name: "Reviewed PRs" }));
		await expect(
			screen.getByRole("button", { name: "Copy links to reviewed pull requests" }),
		).toBeVisible();
		const lastLink = await screen.findByRole("link", {
			name: "A repository with a long name that wraps onto several lines #102",
		});
		await expectSettledVisible(lastLink);
		const viewport = lastLink.closest<HTMLElement>('[data-slot="scroll-area-viewport"]');
		if (!viewport) {
			throw new Error("The reviewed-work list must have a scroll viewport");
		}
		await expect(viewport.scrollHeight).toBeLessThanOrEqual(viewport.clientHeight + 1);
		await expect(lastLink.getBoundingClientRect().bottom).toBeLessThanOrEqual(
			viewport.getBoundingClientRect().bottom + 1,
		);
	},
};

export const LongReviewList: Story = {
	args: {
		reviewedPullRequests: mockPullRequests.flatMap((pullRequest, repositoryIndex) =>
			Array.from({ length: 6 }, (_, index) => {
				const id = repositoryIndex * 6 + index + 1;
				const number = id + 100;
				return {
					...pullRequest,
					id,
					number,
					htmlUrl: `https://github.com/${pullRequest.repository.nameWithOwner}/pull/${number}`,
				};
			}),
		),
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show 12 reviewed pull requests" }));
		await expectSettledVisible(await screen.findByRole("dialog", { name: "Reviewed PRs" }));
		const lastLink = screen.getByRole("link", { name: "Hephaestus #106" });
		const viewport = lastLink.closest<HTMLElement>('[data-slot="scroll-area-viewport"]');
		if (!viewport) {
			throw new Error("The reviewed-work list must have a scroll viewport");
		}
		await expect(viewport.clientHeight).toBeLessThanOrEqual(200);
		await expect(viewport.scrollHeight).toBeGreaterThan(viewport.clientHeight);
		for (let tab = 0; tab < 16 && document.activeElement !== lastLink; tab += 1) {
			await userEvent.tab();
		}
		await expect(lastLink).toHaveFocus();
		await expect(viewport.scrollTop).toBeGreaterThan(0);
		await expect(lastLink.getBoundingClientRect().bottom).toBeLessThanOrEqual(
			viewport.getBoundingClientRect().bottom + 1,
		);
	},
};
