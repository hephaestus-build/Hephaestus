import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";
import type { GitHubAccessOffer } from "@/api/types.gen";
import { minutesBefore } from "@/components/common/story-clock";
import { withStandardPage } from "@/stories/decorators";
import { GithubAccessPage } from "./GithubAccessPage";

const offer: GitHubAccessOffer = {
	workspaceId: 1,
	workspaceSlug: "engineering",
	workspaceName: "Engineering",
	targetId: 2,
	organization: "example-org",
	scopeName: "Organization",
	enrolled: true,
	managed: true,
	paused: false,
	revocationRequested: false,
	externalState: "PENDING",
	confirmedAt: minutesBefore(2),
	invitationUrl: "https://github.com/orgs/example-org/invitation",
};
const meta = {
	component: GithubAccessPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { state: { status: "ready", offers: [offer] }, onEnroll: fn() },
} satisfies Meta<typeof GithubAccessPage>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Invitation: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Open GitHub invitation" })).toHaveAttribute(
			"href",
			offer.invitationUrl,
		);
	},
};
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("group", { name: "Loading your GitHub access" })).toBeVisible();
	},
};
export const ErrorState: Story = {
	args: { state: { status: "error", error: new Error("Service unavailable"), onRetry: fn() } },
};
export const Empty: Story = { args: { state: { status: "ready", offers: [] } } };
export const PausedDeparture: Story = {
	args: {
		state: {
			status: "ready",
			offers: [{ ...offer, enrolled: false, paused: true, revocationRequested: true }],
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Removal pending — access remains until GitHub confirms it."),
		).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Rejoin if eligible" })).toBeEnabled();
	},
};
export const WaitingForOrganization: Story = {
	args: {
		state: {
			status: "ready",
			offers: [
				{
					...offer,
					scopeName: "Developers",
					externalState: "WAITING_ORGANIZATION",
					managed: false,
				},
			],
		},
	},
};
