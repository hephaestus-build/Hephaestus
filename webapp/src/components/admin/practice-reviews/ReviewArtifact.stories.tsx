import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/test/reflow";

import { ReviewArtifactLabel, ReviewArtifactLink } from "./ReviewArtifact";
import {
	gitlabMergeRequest,
	outlineDocument,
	reviewArtifact,
	type ReviewWork,
	slackConversation,
	trackerIssue,
} from "./story-mock-data";

/** The work as a run names it: the server's label, and the provider's mark since the run records one. */
const onRun = ({ reviewedWork, provider }: ReviewWork) => ({ artifact: reviewedWork, provider });

/**
 * The glyph is the forge when the caller knows it, not the kind: the words already say `#1423` or
 * `#engineering`, so a second pull-request icon would repeat them and leave a GitHub request
 * indistinguishable from a GitLab one.
 */
const meta = {
	title: "Workspace admin/Practice reviews/Building blocks/Reviewed work",
	component: ReviewArtifactLabel,
	parameters: { layout: "padded", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: onRun(reviewArtifact),
} satisfies Meta<typeof ReviewArtifactLabel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const GitHubPullRequest: Story = {
	name: "GitHub pull request",
	play: async ({ canvas }) => {
		canvas.getByText("ls1intum/Hephaestus · #1423");
	},
};

export const GitLabMergeRequest: Story = {
	name: "GitLab merge request",
	args: onRun(gitlabMergeRequest),
	play: async ({ canvas }) => {
		// GitLab numbers a merge request with a bang; the label is the server's, and follows the forge.
		canvas.getByText("platform/billing-service · !88");
	},
};

/**
 * An issue and a pull request on one forge wear the same glyph, so the label is the whole
 * difference between them.
 */
export const GitHubIssue: Story = {
	name: "GitHub issue",
	args: onRun(trackerIssue),
	play: async ({ canvas }) => {
		canvas.getByText("ls1intum/Hephaestus · #204");
	},
};

export const SlackConversation: Story = { args: onRun(slackConversation) };

export const OutlineDocument: Story = { args: onRun(outlineDocument) };

/**
 * The hover affordance is on the label alone. A title beside it is rendered by the caller, outside
 * the anchor, so the underline can never reach text that is not the link's name.
 */
export const ExternalLink: Story = {
	render: (args) => (
		<div className="space-y-1">
			<ReviewArtifactLink {...args} />
			<p className="text-sm text-muted-foreground">{reviewArtifact.title}</p>
		</div>
	),
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link");
		await expect(link).toHaveAttribute("target", "_blank");
		await expect(canvas.getByText(reviewArtifact.title).closest("a")).toBeNull();
	},
};

/** An observation or a piece of feedback records no provider, so the glyph is the kind's. */
export const ReviewedWorkRef: Story = {
	args: { artifact: reviewArtifact.reviewedWork, provider: undefined },
};

export const WithoutAUrl: Story = {
	render: (args) => <ReviewArtifactLink {...args} />,
	args: { artifact: { ...reviewArtifact.reviewedWork, url: undefined } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const LongTitle: Story = {
	args: {
		artifact: {
			...reviewArtifact.reviewedWork,
			repositoryName: "hephaestus-administration-and-practice-feedback-platform",
		},
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Unresolved: Story = { args: { artifact: undefined } };
