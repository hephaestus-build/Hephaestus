import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";
import { withStandardPage } from "@/stories/decorators";
import { GithubAccessApprovalPage } from "./GithubAccessApprovalPage";

const meta = {
	component: GithubAccessApprovalPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		busy: false,
		state: {
			status: "review",
			preview: {
				workspaceName: "Engineering",
				organization: "example-org",
				installationId: 500,
				groupIds: ["engineering"],
			},
			onPreview: fn(),
			onApprove: fn(),
		},
	},
} satisfies Meta<typeof GithubAccessApprovalPage>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Review: Story = {};
export const Loading: Story = { args: { state: { status: "loading" } } };
export const InvalidLink: Story = { args: { state: { status: "invalid" } } };
export const SignedOut: Story = { args: { state: { status: "signed-out", onSignIn: fn() } } };
export const ErrorState: Story = {
	args: {
		state: {
			status: "error",
			error: new Error("Link your GitHub.com identity first"),
			onRetry: fn(),
			onLinkAccount: fn(),
		},
	},
};
export const BeforeReview: Story = {
	args: { state: { status: "review", onPreview: fn(), onApprove: fn() } },
};
export const Complete: Story = {
	args: { state: { status: "complete" } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "Organization authorization recorded" }),
		).toBeVisible();
		await expect(
			canvas.queryByRole("button", { name: "Authorize this workspace and scope" }),
		).not.toBeInTheDocument();
	},
};
