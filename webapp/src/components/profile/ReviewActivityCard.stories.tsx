import type { Meta, StoryObj } from "@storybook/react";

import type { ProfileReviewActivity } from "@/api/types.gen";
import { withProvider } from "@/stories/decorators";

import { approvedReview, changesRequestedReview, commentedReview } from "./fixtures";
import { ReviewActivityCard, type ReviewActivityCardProps } from "./ReviewActivityCard";

/** The same projection `ReviewActivitySection` makes for each of its rows. */
function cardArgs(activity: ProfileReviewActivity): ReviewActivityCardProps {
	return {
		isLoading: false,
		state: activity.state,
		submittedAt: activity.submittedAt,
		htmlUrl: activity.htmlUrl,
		pullRequest: activity.pullRequest,
		repositoryName: activity.pullRequest?.repository?.name,
		score: activity.score,
	};
}

/**
 * Card component that displays a user's review activity for a pull request / merge request.
 * Shows the review state, submission time, and score earned from the review.
 */
const meta = {
	component: ReviewActivityCard,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"Displays information about a code review performed by the user, including the status and score earned.",
			},
		},
	},
	argTypes: {
		isLoading: {
			description: "Whether the card is in a loading state",
			control: "boolean",
		},
		state: {
			description: "The state of the review",
			control: "select",
			options: ["APPROVED", "CHANGES_REQUESTED", "COMMENTED", "DISMISSED", "PENDING"],
		},
		submittedAt: {
			description: "When the review was submitted (Date object)",
			control: "date",
		},
		htmlUrl: {
			description: "URL to the pull request or review",
			control: "text",
		},
		pullRequest: {
			description: "Pull request details",
			control: "object",
		},
		repositoryName: {
			description: "Name of the repository",
			control: "text",
		},
		score: {
			description: "Points earned for the review",
			control: "number",
		},
	},
	tags: ["autodocs"],
} satisfies Meta<typeof ReviewActivityCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Shows a review where the user approved the pull request and earned points.
 */
export const Approved: Story = {
	args: cardArgs(approvedReview),
};

/**
 * Shows a review where the user requested changes to the pull request.
 */
export const ChangesRequested: Story = {
	args: cardArgs(changesRequestedReview),
};

/**
 * Shows a review where the user only left comments without approving or requesting changes.
 */
export const Commented: Story = {
	args: cardArgs(commentedReview),
};

/**
 * Shows the loading state of the card when data is being fetched.
 */
export const Loading: Story = {
	args: { ...cardArgs(commentedReview), isLoading: true },
};

/**
 * Shows a dismissed review which no longer counts towards the user's score.
 */
export const Dismissed: Story = {
	args: { ...cardArgs(approvedReview), state: "DISMISSED", score: 0 },
};

/**
 * Shows a review with an unknown state.
 */
export const Unknown: Story = {
	args: { ...cardArgs(approvedReview), state: "UNKNOWN", score: 0 },
};

/**
 * Shows a review with code snippet references in the title.
 */
export const WithCodeInTitle: Story = {
	args: {
		...cardArgs(approvedReview),
		pullRequest: {
			...approvedReview.pullRequest,
			title: "Update `LeaderboardTable` component and fix `ProfileContent` layout",
		},
	},
};

// --- Alternate provider variants ---

/**
 * Approved review with alternate provider colors.
 */
export const ApprovedMergeRequest: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		...Approved.args,
		providerType: "GITLAB",
		htmlUrl: "https://gitlab.com/ls1intum/Hephaestus/-/merge_requests/42",
	},
};

/**
 * Unknown review state with provider-native merge request icon.
 */
export const UnknownMergeRequest: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		...Unknown.args,
		providerType: "GITLAB",
		htmlUrl: "https://gitlab.com/ls1intum/Hephaestus/-/merge_requests/42",
	},
};
