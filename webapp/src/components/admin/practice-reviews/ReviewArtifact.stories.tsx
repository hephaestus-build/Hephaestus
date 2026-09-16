import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/test/reflow";

import { ReviewArtifactLabel, ReviewArtifactLink } from "./ReviewArtifact";
import {
	gitlabMergeRequest,
	outlineDocument,
	reviewArtifact,
	slackConversation,
	trackerIssue,
} from "./story-mock-data";

/**
 * The glyph is the forge, not the kind: the words already say `PR #1423` or `#engineering`, so a
 * second pull-request icon would repeat them and leave a GitHub request indistinguishable from a
 * GitLab one.
 */
const meta = {
	component: ReviewArtifactLabel,
	parameters: { layout: "padded", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: { artifact: reviewArtifact },
} satisfies Meta<typeof ReviewArtifactLabel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const GitHubPullRequest: Story = { name: "GitHub pull request" };

export const GitLabMergeRequest: Story = {
	name: "GitLab merge request",
	args: { artifact: gitlabMergeRequest },
	play: async ({ canvas }) => {
		// GitLab calls it a merge request and numbers it with a bang; the words follow the forge.
		canvas.getByText("platform/billing-service · MR !88");
	},
};

/**
 * An issue and a pull request on one forge wear the same glyph, so the wording is the whole
 * difference between them.
 */
export const GitHubIssue: Story = {
	name: "GitHub issue",
	args: { artifact: trackerIssue },
	play: async ({ canvas }) => {
		canvas.getByText("ls1intum/Hephaestus · Issue #204");
	},
};

export const SlackConversation: Story = { args: { artifact: slackConversation } };

export const OutlineDocument: Story = { args: { artifact: outlineDocument } };

/**
 * The hover affordance is on the label alone. The title beside it is rendered by the caller, outside
 * the anchor, so the underline can never reach text that is not the link's name.
 */
export const ExternalLink: Story = {
	render: (args) => (
		<div className="space-y-1">
			<ReviewArtifactLink {...args} />
			<p className="text-sm text-muted-foreground">{args.artifact?.title}</p>
		</div>
	),
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link");
		await expect(link).toHaveAttribute("target", "_blank");
		await expect(canvas.getByText(reviewArtifact.title).closest("a")).toBeNull();
	},
};

export const WithoutAUrl: Story = {
	render: (args) => <ReviewArtifactLink {...args} />,
	args: { artifact: { ...reviewArtifact, url: undefined } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const LongTitle: Story = {
	args: {
		artifact: {
			...reviewArtifact,
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
