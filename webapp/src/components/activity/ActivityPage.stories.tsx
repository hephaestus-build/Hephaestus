import type { Meta, StoryObj } from "@storybook/react-vite";
import { Link } from "@tanstack/react-router";
import { expect, fn } from "storybook/test";

import { buttonVariants } from "@/components/ui/button";
import {
	ada,
	GITLAB_OPEN_WORK,
	GITLAB_WORK_LOG,
	NOTHING_OPEN,
	OPEN_WORK,
	OVERVIEW,
	QUIET_OVERVIEW,
	readyOverview,
	WORK_LOG,
	YEAR_OVERVIEW,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ActivityPage } from "./ActivityPage";

const onRetry = fn();
const onCopy = fn(async () => {
	/* the copy is the route's */
});

const readyTimeline = (items = WORK_LOG) => ({
	status: "ready" as const,
	stale: false,
	items,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
	onCopy,
});

const meta = {
	component: ActivityPage,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		account: { status: "ready", login: ada.login },
		range: "30d",
		onRangeChange: fn(),
		openWork: { status: "ready", openWork: OPEN_WORK },
		overview: readyOverview(OVERVIEW),
		timeline: readyTimeline(),
	},
	argTypes: { account: { control: false } },
} satisfies Meta<typeof ActivityPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		// Action before history: what needs you, what is assigned, then the range, then the timeline.
		const headings = canvas
			.getAllByRole("heading", { level: 2 })
			.map((heading) => heading.textContent);
		await expect(headings).toStrictEqual([
			"Needs you",
			"Assigned issues",
			"Last 30 days",
			"Timeline",
		]);
		await userEvent.click(canvas.getByRole("button", { name: "90 days" }));
		await expect(args.onRangeChange).toHaveBeenCalledWith("90d");
		await userEvent.click(canvas.getByRole("button", { name: "Copy as Markdown" }));
		await expect(onCopy).toHaveBeenCalledOnce();
	},
};

/**
 * While another range loads, the previous range's figures stand in, drained of colour and marked
 * busy — and, after a moment, the range control says so in words too.
 */
export const Updating: Story = {
	args: { overview: { status: "ready", overview: OVERVIEW, stale: true } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Updating…", undefined, { timeout: 3000 })).toBeVisible();
		await expect(canvas.getByRole("status")).toHaveTextContent("Updating…");
	},
};

/** A first week: nothing open, nothing done, every region saying so in a line. */
export const FirstWeek: Story = {
	args: {
		openWork: { status: "ready", openWork: NOTHING_OPEN },
		overview: readyOverview(QUIET_OVERVIEW),
		timeline: readyTimeline([]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing needs you")).toBeVisible();
		await expect(canvas.getByText("No activity in this range")).toBeVisible();
		await expect(
			canvas.queryByRole("button", { name: "Copy as Markdown" }),
		).not.toBeInTheDocument();
	},
};

export const TwelveMonths: Story = {
	args: { range: "1y", overview: readyOverview(YEAR_OVERVIEW, "1y") },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 2, name: "Last 12 months" })).toBeVisible();
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		providerType: "GITLAB",
		openWork: { status: "ready", openWork: GITLAB_OPEN_WORK },
		timeline: readyTimeline(GITLAB_WORK_LOG),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /^Merge requests/u })).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

/** Until the membership says whose activity this is, every region holds its shape. */
export const Loading: Story = {
	args: {
		account: { status: "loading" },
		openWork: { status: "loading" },
		overview: { status: "loading" },
		timeline: { status: "loading" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Activity" })).toBeVisible();
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};

export const MembershipFailed: Story = {
	args: { account: { status: "error", error: new Error("Network down"), onRetry } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.queryByRole("heading", { name: "Needs you" })).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

export const NoConnectedAccount: Story = {
	args: {
		account: {
			status: "none",
			settingsLink: (
				<Link
					to="/settings"
					hash="linked-accounts-heading"
					className={buttonVariants({ variant: "outline" })}
				>
					Connect an account
				</Link>
			),
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 2, name: "No connected account" }),
		).toBeVisible();
		await expect(canvas.queryByRole("heading", { name: "Needs you" })).not.toBeInTheDocument();
	},
};

/** In a user view the settings a link would open are the viewer's, so the state offers none. */
export const NoConnectedAccountInUserView: Story = {
	args: { account: { status: "none" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};
