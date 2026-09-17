import type { Meta, StoryObj } from "@storybook/react";
import { RefreshCw } from "lucide-react";

import type { PullRequestInfo } from "@/api/types.gen";
import {
	closedPullRequest,
	draftPullRequest,
	mergedPullRequest,
	openPullRequest,
} from "@/components/profile/fixtures";
import { Button } from "@/components/ui/button";
import { withProvider } from "@/stories/decorators";

import { IssueCard, type IssueCardProps } from "./IssueCard";

/** The same projection `OpenPullRequestsSection` makes for each of its rows. */
function cardArgs(pullRequest: PullRequestInfo): IssueCardProps {
	return {
		providerType: "GITHUB",
		isLoading: false,
		additions: pullRequest.additions,
		deletions: pullRequest.deletions,
		number: pullRequest.number,
		repositoryName: pullRequest.repository?.name,
		title: pullRequest.title,
		htmlUrl: pullRequest.htmlUrl,
		state: pullRequest.state,
		isDraft: pullRequest.isDraft,
		isMerged: pullRequest.isMerged,
		createdAt: pullRequest.createdAt,
		pullRequestLabels: pullRequest.labels,
	};
}

/**
 * Card component for displaying pull requests / merge requests with metadata.
 * Shows details such as title, repository, state, labels, and creation date.
 * Used primarily in user profile pages to display contribution activity.
 */
const meta = {
	component: IssueCard,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"A card that displays pull request / merge request information with metadata like state, labels, and repository.",
			},
		},
	},
	argTypes: {
		isLoading: {
			description: "Whether the card is in a loading state",
			control: "boolean",
		},
		title: {
			description: "Title of the issue or pull request",
			control: "text",
		},
		number: {
			description: "Issue or pull request number",
			control: "number",
		},
		additions: {
			description: "Number of added lines (for PRs)",
			control: "number",
		},
		deletions: {
			description: "Number of deleted lines (for PRs)",
			control: "number",
		},
		htmlUrl: {
			description: "Link to the pull request or merge request",
			control: "text",
		},
		repositoryName: {
			description: "Name of the repository",
			control: "text",
		},
		createdAt: {
			description: "Creation date (Date object)",
			control: "date",
		},
		state: {
			description: "Current state of the issue/PR",
			control: "select",
			options: ["OPEN", "CLOSED"],
		},
		isDraft: {
			description: "Whether the PR is a draft",
			control: "boolean",
		},
		isMerged: {
			description: "Whether the PR has been merged",
			control: "boolean",
		},
		pullRequestLabels: {
			description: "Labels attached to the issue/PR",
			control: "object",
		},
		noLinkWrapper: {
			description: "If true, the card will not be wrapped in an <a> tag",
			control: "boolean",
		},
		rightContent: {
			description: "Additional content to display in the right side of the card header",
		},
	},
	tags: ["autodocs"],
} satisfies Meta<typeof IssueCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * An open pull request with enhancement and frontend labels.
 */
export const OpenPullRequest: Story = {
	args: cardArgs(openPullRequest),
};

/**
 * A draft pull request for major refactoring work that's still in progress.
 */
export const DraftPullRequest: Story = {
	args: cardArgs(draftPullRequest),
};

/**
 * A merged pull request that fixed a critical bug with high priority.
 */
export const MergedPullRequest: Story = {
	args: cardArgs(mergedPullRequest),
};

/**
 * A closed pull request that was not merged, typically indicating the proposed changes were rejected.
 */
export const ClosedPullRequest: Story = {
	args: cardArgs(closedPullRequest),
};

/**
 * Example of the card in a loading state, showing skeletons.
 */
export const Loading: Story = {
	args: {
		providerType: "GITHUB",
		isLoading: true,
	},
};

/**
 * Example of a card with no wrapper link, making just the repository name and number clickable.
 */
export const WithNoLinkWrapper: Story = {
	args: {
		...OpenPullRequest.args,
		noLinkWrapper: true,
	},
};

/**
 * Example of a card with custom right content, such as an action button.
 */
export const WithRightContent: Story = {
	args: {
		...OpenPullRequest.args,
		rightContent: (
			<Button variant="outline" size="sm" className="ml-4">
				<RefreshCw className="mr-1 size-3.5" />
				<span className="text-xs">Refresh</span>
			</Button>
		),
	},
};

/**
 * Example with both no link wrapper and custom right content.
 */
export const WithNoLinkWrapperAndRightContent: Story = {
	args: {
		...OpenPullRequest.args,
		noLinkWrapper: true,
		rightContent: (
			<Button variant="outline" size="sm" className="ml-4">
				<RefreshCw className="mr-1 size-3.5" />
				<span className="text-xs">Analyze</span>
			</Button>
		),
	},
};

// --- Alternate provider variants ---

/**
 * Open merge request with provider-native icon and green color.
 */
export const OpenMergeRequest: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		...OpenPullRequest.args,
		providerType: "GITLAB",
		htmlUrl: "https://gitlab.com/ls1intum/Hephaestus/-/merge_requests/42",
	},
};

/**
 * Draft merge request with muted color.
 */
export const DraftMergeRequest: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		...DraftPullRequest.args,
		providerType: "GITLAB",
		htmlUrl: "https://gitlab.com/ls1intum/Artemis/-/merge_requests/87",
	},
};

/**
 * Merged merge request with blue merged color (vs GitHub purple).
 */
export const MergedMergeRequest: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		...MergedPullRequest.args,
		providerType: "GITLAB",
		htmlUrl: "https://gitlab.com/ls1intum/Athena/-/merge_requests/103",
	},
};

/**
 * Closed merge request (not merged).
 */
export const ClosedMergeRequest: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		...ClosedPullRequest.args,
		providerType: "GITLAB",
		htmlUrl: "https://gitlab.com/ls1intum/Hephaestus/-/merge_requests/75",
	},
};
