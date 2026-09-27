import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import {
	LARGE_ROSTER,
	LARGE_WORKSPACE_OVERVIEW,
	MEMBERS,
	readyMembers,
	readyOverview,
	WORKSPACE_OVERVIEW,
	WORKSPACE_WORK_LOG,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { settledPopup } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";

import { WorkspaceActivityPage } from "./WorkspaceActivityPage";

const onCopy = fn(async () => {
	/* the copy is the route's */
});

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
		overview: readyOverview(WORKSPACE_OVERVIEW),
		members: readyMembers(MEMBERS),
		timeline: {
			status: "ready",
			stale: false,
			items: WORKSPACE_WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
			onCopy,
		},
	},
} satisfies Meta<typeof WorkspaceActivityPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const headings = canvas
			.getAllByRole("heading", { level: 2 })
			.map((heading) => heading.textContent);
		await expect(headings).toStrictEqual(["Last 30 days", "Members", "Timeline"]);
		// The workspace's tiles add up its members' rows.
		await expect(canvas.getByRole("link", { name: /^Pull requests\s*7 merged/u })).toBeVisible();
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

/** 250 members, most of them quiet: the table shows 50 by name and finds the rest. */
export const LargeWorkspace: Story = {
	args: {
		overview: readyOverview(LARGE_WORKSPACE_OVERVIEW),
		members: readyMembers(LARGE_ROSTER),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Show all 250" })).toBeVisible();
	},
};

export const OneTeam: Story = {
	args: { teamId: 2 },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Team" })).toHaveTextContent(
			"Platform / Payments",
		);
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
		await expect(canvas.getByText("5 members · 3 active")).toBeVisible();
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

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Loading: Story = {
	args: {
		overview: { status: "loading" },
		members: { status: "loading" },
		timeline: { status: "loading" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/members ·/u)).not.toBeInTheDocument();
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};
