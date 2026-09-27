import type { Meta, StoryObj } from "@storybook/react-vite";
import { Link } from "@tanstack/react-router";
import { expect, fn } from "storybook/test";

import { buttonVariants } from "@/components/ui/button";
import {
	ada,
	NOTHING_OPEN,
	OPEN_WORK,
	QUIET_SUMMARY,
	SUMMARY,
	TIMELINE,
} from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";

import { ActivityPage } from "./ActivityPage";

const onRetry = fn();

const meta = {
	component: ActivityPage,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		account: { status: "ready", login: ada.login },
		range: "7d",
		onRangeChange: fn(),
		openWork: { status: "ready", openWork: OPEN_WORK },
		summary: { status: "ready", summary: SUMMARY },
		timeline: {
			status: "ready",
			items: TIMELINE,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
		},
	},
	argTypes: { account: { control: false } },
} satisfies Meta<typeof ActivityPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// What waits on you comes before what you did.
		const headings = canvas
			.getAllByRole("heading", { level: 2 })
			.map((heading) => heading.textContent);
		await expect(headings).toStrictEqual([
			expect.stringContaining("Waiting on you"),
			expect.stringContaining("Your open pull requests"),
			expect.stringContaining("Assigned to you"),
			"Summary",
			"Recent activity",
		]);
	},
};

export const FirstWeek: Story = {
	args: {
		openWork: { status: "ready", openWork: NOTHING_OPEN },
		summary: { status: "ready", summary: QUIET_SUMMARY },
		timeline: {
			status: "ready",
			items: [],
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
		},
	},
};

export const GitLab: Story = {
	args: {
		providerType: "GITLAB",
		timeline: {
			status: "ready",
			items: [],
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Your merge requests, reviews, issues and comments show up here."),
		).toBeVisible();
	},
};

/** Until the membership says whose activity this is, every region holds its shape. */
export const Loading: Story = {
	args: {
		account: { status: "loading" },
		openWork: { status: "loading" },
		summary: { status: "loading" },
		timeline: { status: "loading" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Activity" })).toBeVisible();
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const MembershipFailed: Story = {
	args: { account: { status: "error", error: new Error("Network down"), onRetry } },
	play: async ({ canvas, userEvent }) => {
		await expect(
			canvas.queryByRole("heading", { name: /Waiting on you/u }),
		).not.toBeInTheDocument();
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
		await expect(canvas.getByRole("heading", { level: 1, name: "Activity" })).toBeVisible();
		await expect(
			canvas.queryByRole("heading", { name: /Waiting on you/u }),
		).not.toBeInTheDocument();
	},
};

/** In a user view the settings a link would open are the viewer's, so the state offers none. */
export const NoConnectedAccountInUserView: Story = {
	args: { account: { status: "none" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};
