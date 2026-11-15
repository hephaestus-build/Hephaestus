import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import {
	gitlabMergeRequest,
	outlineDocument,
	reviewItem,
	slackConversation,
	trackerIssue,
} from "./fixtures";
import { ReviewItemLabel, ReviewItemLink } from "./ReviewItem";

const meta = {
	component: ReviewItemLabel,
	parameters: { layout: "padded", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: { item: reviewItem },
} satisfies Meta<typeof ReviewItemLabel>;

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
	args: { item: gitlabMergeRequest },
	play: async ({ canvas }) => {
		canvas.getByText("platform/billing-service · !88");
	},
};

export const GitHubIssue: Story = {
	name: "GitHub issue",
	args: { item: trackerIssue },
	play: async ({ canvas }) => {
		canvas.getByText("ls1intum/Hephaestus · #204");
	},
};

export const SlackConversation: Story = { args: { item: slackConversation } };

export const OutlineDocument: Story = { args: { item: outlineDocument } };

const TITLE = "Cache the workspace member lookup on the review path";

export const ExternalLink: Story = {
	args: { item: { ...reviewItem, title: TITLE } },
	render: (args) => (
		<div className="space-y-1">
			<ReviewItemLink {...args} />
			<p className="text-sm text-muted-foreground">{TITLE}</p>
		</div>
	),
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link");
		await expect(link).toHaveAttribute("target", "_blank");
		await expect(canvas.getByText(TITLE).closest("a")).toBeNull();
	},
};

export const WithoutAProvider: Story = {
	args: { item: { ...reviewItem, provider: undefined } },
};

export const WithoutAUrl: Story = {
	render: (args) => <ReviewItemLink {...args} />,
	args: { item: { ...reviewItem, url: undefined } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const LongTitle: Story = {
	args: {
		item: {
			...reviewItem,
			container: "hephaestus-administration-and-practice-feedback-platform",
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

export const Unresolved: Story = { args: { item: undefined } };
