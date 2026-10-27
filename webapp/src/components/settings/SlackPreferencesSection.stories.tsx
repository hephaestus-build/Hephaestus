import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { StandardPageSurface } from "@/components/layout/StandardPageSurface";
import { expectNoPageOverflow } from "@/stories/reflow";

import { SlackPreferencesSection } from "./SlackPreferencesSection";

const meta = {
	component: SlackPreferencesSection,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		onConnectSlack: fn(),
		onToggleChannelMessages: fn(),
		onRetry: fn(),
	},
} satisfies Meta<typeof SlackPreferencesSection>;

export default meta;
type Story = StoryObj<typeof meta>;

const workspace = {
	workspaceSlug: "hephaestustest",
	workspaceName: "Hephaestus Test",
	slackTeamId: "T1",
	slackTeamName: "hephaestus-test",
	slackUserId: "U1",
	slackDisplayName: "Jordan",
	channelMessagesAllowed: true,
	activeMonitoredChannelCount: 2,
};

export const Linked: Story = {
	args: {
		workspaces: [workspace],
		isSlackLinked: true,
		canConnectSlack: true,
	},
};

/**
 * Turning message use OFF deletes already-collected channel messages, so the switch cannot do it
 * alone — the OFF transition is gated by a destructive confirmation. Turning it ON stays instant.
 */
export const ConfirmTurningOff: Story = {
	args: {
		workspaces: [workspace],
		isSlackLinked: true,
		canConnectSlack: true,
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("switch", { name: /use your new channel messages/iu }));
		// Flipping the switch alone must not delete anything.
		await expect(args.onToggleChannelMessages).not.toHaveBeenCalled();

		const confirm = await screen.findByRole("button", { name: "Stop using and delete" });
		await userEvent.click(confirm);
		await expect(args.onToggleChannelMessages).toHaveBeenCalledWith("hephaestustest", false);
	},
};

/** Message use already off — switching it back ON is not destructive, so it fires immediately. */
export const MessageUseOff: Story = {
	args: {
		workspaces: [{ ...workspace, channelMessagesAllowed: false }],
		isSlackLinked: true,
		canConnectSlack: true,
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("switch", { name: /use your new channel messages/iu }));
		await expect(args.onToggleChannelMessages).toHaveBeenCalledWith("hephaestustest", true);
	},
};

/**
 * Two workspaces save at once. Each switch stays held until its own save finishes, and a workspace
 * with no save in progress stays available.
 */
export const SavingTwoWorkspaces: Story = {
	args: {
		workspaces: [
			workspace,
			{ ...workspace, workspaceSlug: "research", workspaceName: "Research", slackTeamId: "T2" },
			{ ...workspace, workspaceSlug: "teaching", workspaceName: "Teaching", slackTeamId: "T3" },
		],
		isSlackLinked: true,
		canConnectSlack: true,
		updatingWorkspaceSlugs: new Set(["hephaestustest", "research"]),
	},
	play: async ({ canvas }) => {
		const channelMessages = (workspaceName: string) =>
			within(canvas.getByRole("group", { name: `${workspaceName} Slack preferences` })).getByRole(
				"switch",
				{ name: "Use your new channel messages" },
			);
		for (const saving of ["Hephaestus Test", "Research"]) {
			await expect(channelMessages(saving)).toHaveAttribute("aria-disabled", "true");
			await expect(channelMessages(saving)).toHaveAttribute("aria-busy", "true");
		}
		await expect(channelMessages("Teaching")).not.toHaveAttribute("aria-disabled", "true");
		await expect(channelMessages("Teaching")).not.toHaveAttribute("aria-busy", "true");
	},
};

export const NotLinked: Story = {
	args: {
		workspaces: [],
		isSlackLinked: false,
		canConnectSlack: true,
	},
};

export const ConnectedWithoutWorkspace: Story = {
	args: {
		workspaces: [],
		isSlackLinked: true,
		canConnectSlack: true,
	},
};

/**
 * The preferences in the shape they load into, under the section's own heading. Nothing is claimed
 * about the connection or any workspace until they arrive.
 */
export const Loading: Story = {
	args: {
		workspaces: [],
		isSlackLinked: true,
		canConnectSlack: true,
		isLoading: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 2, name: "Slack" })).toBeVisible();
		await expect(
			canvas.getByText(/choose whether Hephaestus can use your new messages/iu),
		).toBeVisible();
		await expect(canvas.getByRole("group", { name: "Loading Slack preferences" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByRole("switch")).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.queryByRole("heading", { level: 3 })).toBeNull();
		await expect(canvas.queryByText(/connected|not available|message use/iu)).toBeNull();
	},
};

/**
 * On a 320px phone, inside the settings page's own padding, the placeholder stays inside its box.
 * The width is set on the page here rather than by the viewport, so the story measures the same
 * layout whatever the canvas size.
 */
export const PhoneLoading: Story = {
	args: {
		workspaces: [],
		isSlackLinked: true,
		canConnectSlack: true,
		isLoading: true,
	},
	decorators: [
		(Story) => (
			<StandardPageSurface
				role="region"
				aria-label="Settings on a phone"
				className="w-80 max-w-full"
			>
				<Story />
			</StandardPageSurface>
		),
	],
	play: async ({ canvas }) => {
		// The page is 320px wide, or the whole canvas where that is narrower.
		const page = canvas.getByRole("region", { name: "Settings on a phone" });
		await expect(page.getBoundingClientRect().width).toBe(
			Math.min(320, document.documentElement.clientWidth),
		);
		await expectNoPageOverflow();
		await expect(page.scrollWidth).toBeLessThanOrEqual(page.clientWidth);

		// A page-wide check cannot see a row that spills out of the placeholder itself.
		const loading = canvas.getByRole("group", { name: "Loading Slack preferences" });
		await expect(loading).toHaveAttribute("aria-busy", "true");
		await expect(loading.scrollWidth).toBeLessThanOrEqual(loading.clientWidth);

		const box = loading.getBoundingClientRect();
		const style = getComputedStyle(loading);
		const contentLeft = box.left + loading.clientLeft + Number.parseFloat(style.paddingLeft);
		const contentRight =
			box.left + loading.clientLeft + loading.clientWidth - Number.parseFloat(style.paddingRight);
		const skeletons = [...loading.querySelectorAll<HTMLElement>('[data-slot="skeleton"]')];
		await expect(skeletons.length).toBeGreaterThan(0);
		for (const skeleton of skeletons) {
			const { left, right } = skeleton.getBoundingClientRect();
			await expect(left).toBeGreaterThanOrEqual(contentLeft);
			await expect(right).toBeLessThanOrEqual(contentRight);
		}
	},
};

/** Failed load → the shared error alert, with a retry. */
export const ErrorState: Story = {
	args: {
		workspaces: [],
		isSlackLinked: true,
		canConnectSlack: true,
		isError: true,
		error: { detail: "Slack is not responding." },
	},
	play: async ({ args, canvas }) => {
		canvas.getByText(/could not load your slack preferences/iu);
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};
