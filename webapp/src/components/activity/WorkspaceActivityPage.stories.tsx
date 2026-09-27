import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { MEMBERS, WORKSPACE_SUMMARY, WORKSPACE_TIMELINE } from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";
import { settledPopup } from "@/stories/overlay";

import { WorkspaceActivityPage } from "./WorkspaceActivityPage";

const meta = {
	component: WorkspaceActivityPage,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		range: "30d",
		onRangeChange: fn(),
		teams: {
			status: "ready",
			teams: [
				{ id: 1, name: "Platform" },
				{ id: 2, name: "Platform / Payments" },
			],
		},
		teamId: undefined,
		onTeamChange: fn(),
		summary: { status: "ready", summary: WORKSPACE_SUMMARY },
		members: { status: "ready", members: MEMBERS },
		timeline: {
			status: "ready",
			items: WORKSPACE_TIMELINE,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
		},
	},
} satisfies Meta<typeof WorkspaceActivityPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("two of three members active")).toBeVisible();
		await userEvent.click(canvas.getByRole("combobox", { name: "Team" }));
		const options = within(await settledPopup()).getAllByRole("option");
		await expect(options.map((option) => option.textContent)).toStrictEqual([
			"Everyone",
			"Platform",
			"Platform / Payments",
		]);
		await userEvent.click(screen.getByRole("option", { name: "Platform / Payments" }));
		await expect(args.onTeamChange).toHaveBeenCalledWith(2);
	},
};

export const OneTeam: Story = {
	args: { teamId: 2 },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "Members of Platform / Payments" }),
		).toBeVisible();
	},
};

export const NoTeams: Story = {
	args: { teams: { status: "ready", teams: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("combobox", { name: "Team" })).not.toBeInTheDocument();
	},
};

/** The team filter waits for the teams; the rest of the page does not. */
export const TeamsLoading: Story = {
	args: { teams: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("combobox", { name: "Team" })).not.toBeInTheDocument();
		await expect(canvas.getByText("two of three members active")).toBeVisible();
	},
};

const retryTeams = fn();

export const TeamsFailed: Story = {
	args: { teams: { status: "error", error: new Error("Network down"), onRetry: retryTeams } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.queryByRole("combobox", { name: "Team" })).not.toBeInTheDocument();
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("Couldn't load teams");
		await userEvent.click(within(alert).getByRole("button", { name: "Retry" }));
		await expect(retryTeams).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = {
	args: {
		summary: { status: "loading" },
		members: { status: "loading" },
		timeline: { status: "loading" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/members active/u)).not.toBeInTheDocument();
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};
