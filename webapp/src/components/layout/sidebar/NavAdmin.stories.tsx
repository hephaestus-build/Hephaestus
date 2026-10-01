import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { SidebarProvider } from "@/components/ui/sidebar";

import { NavAdmin } from "./NavAdmin";
import { withSidebarFrame } from "./sidebar-story-frame";

const meta = {
	component: NavAdmin,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "aet",
		integrationKinds: ["GITHUB", "SLACK", "OUTLINE"],
	},
	decorators: [withSidebarFrame],
} satisfies Meta<typeof NavAdmin>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const GitLabWorkspace: Story = {
	args: {
		scmProviderType: "GITLAB",
		integrationKinds: ["GITLAB", "SLACK", "OUTLINE"],
	},
};

export const OwnerNavigation: Story = {
	args: { isOwner: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Member onboarding" })).toHaveAttribute(
			"href",
			"/w/aet/admin/onboarding",
		);
		// Onboarding is a members concern, so it sits with Members rather than at the top.
		const labels = canvas.getAllByRole("link").map((link) => link.textContent);
		await expect(labels.indexOf("Member onboarding")).toBe(labels.indexOf("Members") + 1);
	},
};

export const ExpandedNavigation: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Practices" }));
		await userEvent.click(canvas.getByRole("button", { name: "Integrations" }));

		canvas.getByRole("link", { name: "Practice setup" });
		canvas.getByRole("link", { name: "Practice reviews" });
		canvas.getByRole("link", { name: "Overview" });
		canvas.getByRole("link", { name: "GitHub" });
	},
};

export const OptionalIntegrationsUnavailable: Story = {
	args: {
		integrationKinds: ["GITHUB"],
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Integrations" }));

		canvas.getByRole("link", { name: "Overview" });
		canvas.getByRole("link", { name: "GitHub" });
		await expect(canvas.queryByRole("link", { name: "Slack" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("link", { name: "Outline" })).not.toBeInTheDocument();
	},
};

/**
 * Feedback awaiting an admin's approval is counted beside Practice reviews, so a decision owed is
 * seen from anywhere in the workspace — and on the Practices section itself while it is closed, so
 * folding the section does not hide it. The count is part of the name, not only its picture.
 */
export const FeedbackAwaitingApproval: Story = {
	args: { awaitingApproval: 7 },
	play: async ({ canvas, userEvent }) => {
		const section = canvas.getByRole("button", {
			name: "Practices (7 pieces of feedback awaiting approval)",
		});
		await expect(canvas.getByText("7")).toBeVisible();
		await userEvent.click(section);
		// Open, the count moves to the entry it belongs to.
		await expect(
			canvas.getByRole("link", {
				name: "Practice reviews (7 pieces of feedback awaiting approval)",
			}),
		).toHaveAttribute("href", "/w/aet/admin/practices/reviews");
		canvas.getByRole("button", { name: "Practices" });
		await expect(canvas.getAllByText("7")).toHaveLength(1);
	},
};

/** The sidebar folded to icons: the section is a link, and its name still carries the count. */
export const FeedbackAwaitingApprovalFolded: Story = {
	args: { awaitingApproval: 7 },
	decorators: [
		(Story) => (
			<SidebarProvider defaultOpen={false} className="min-h-0">
				<Story />
			</SidebarProvider>
		),
	],
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: "Practices (7 pieces of feedback awaiting approval)" }),
		).toHaveAttribute("href", "/w/aet/admin/practices/reviews");
	},
};

/** One awaiting: the name counts it in the singular. */
export const OnePieceAwaitingApproval: Story = {
	args: { awaitingApproval: 1 },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: "Practices (1 piece of feedback awaiting approval)" });
	},
};

/** None awaiting: no badge, and no "0" to read past. */
export const NothingAwaitingApproval: Story = {
	args: { awaitingApproval: 0 },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Practices" }));
		canvas.getByRole("link", { name: "Practice reviews" });
		await expect(canvas.queryByText("0")).not.toBeInTheDocument();
	},
};
