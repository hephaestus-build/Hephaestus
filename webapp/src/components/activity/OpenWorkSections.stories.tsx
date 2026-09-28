import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { PageLayout } from "@/components/layout/PageLayout";
import {
	ada,
	GITLAB_OPEN_WORK,
	MANY_REVIEW_REQUESTS,
	NOTHING_OPEN,
	ONLY_WAITING,
	OPEN_WORK,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { OpenWorkSections } from "./OpenWorkSections";

/**
 * Open work grouped by the action it needs, as GitHub's pull request inbox and GitLab's merge
 * request homepage group it. A review request other reviewers already settled is not work to do,
 * so it waits folded away with the rest of what waits on someone else — reversibly, with no setting.
 */
const meta = {
	component: OpenWorkSections,
	decorators: [
		// The two sections are siblings on the Activity page, which spaces them.
		(Story) => (
			<PageLayout className="space-y-8">
				<Story />
			</PageLayout>
		),
		withStandardPage,
	],
	tags: ["autodocs"],
	args: {
		state: { status: "ready", openWork: OPEN_WORK, login: ada.login },
		providerType: "GITHUB",
		perspective: "self",
	},
} satisfies Meta<typeof OpenWorkSections>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		// Only what needs Ada is open: a request, what was returned to her, what is approved.
		const [requested, returned, approved, ...rest] = canvas.getAllByRole("heading", { level: 3 });
		await expect(requested).toHaveAccessibleName("Review requested 2");
		await expect(returned).toHaveAccessibleName("Returned to you 2");
		await expect(approved).toHaveAccessibleName("Approved 1");
		await expect(rest).toEqual([]);
		// Approved and requested on one pull request of six reviewers: four faces, then the rest.
		const reviewers = canvas.getAllByRole("list", { name: "Reviewers" });
		await expect(reviewers.some((list) => list.textContent.includes("+2"))).toBe(true);
		await expect(canvas.getByRole("img", { name: "Bob Brenner requested changes" })).toBeVisible();
		// What waits on others is folded, with its count, until asked for.
		const waiting = canvas.getByRole("button", { name: /Waiting on others/u });
		await expect(waiting).toHaveAccessibleName("Waiting on others · 7");
		await expect(waiting).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(waiting);
		await expect(
			canvas.getByRole("heading", { name: /Covered by other reviewers/u }),
		).toBeVisible();
		await expect(canvas.getByRole("heading", { name: /Drafts/u })).toBeVisible();
		await expect(canvas.getByRole("heading", { name: "Requested from your team 2" })).toBeVisible();
		// A request Ada already approved, still listed as GitLab lists it, waits as hers done.
		await expect(canvas.getByRole("heading", { name: "Reviewed by you 1" })).toBeVisible();
	},
};

/** The server lists the most recently updated team requests and says there are more. */
export const MoreTeamRequestsThanListed: Story = {
	args: {
		state: {
			status: "ready",
			openWork: {
				...OPEN_WORK,
				teamReviewRequests: { ...OPEN_WORK.teamReviewRequests, hasMore: true },
			},
			login: ada.login,
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Showing the 2 most recently updated of your teams' review requests."),
		).toBeVisible();
	},
};

/** Nothing needs Ada; what she has open waits on reviewers, and the one request is covered. */
export const NothingNeedsYou: Story = {
	args: { state: { status: "ready", openWork: ONLY_WAITING, login: ada.login } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing needs you")).toBeVisible();
		await expect(canvas.getByRole("button", { name: /Waiting on others/u })).toHaveAccessibleName(
			"Waiting on others · 3",
		);
		await expect(canvas.getByText("No issues assigned to you")).toBeVisible();
	},
};

export const NothingOpen: Story = {
	args: { state: { status: "ready", openWork: NOTHING_OPEN, login: ada.login } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing needs you")).toBeVisible();
		await expect(
			canvas.queryByRole("button", { name: /Waiting on others/u }),
		).not.toBeInTheDocument();
	},
};

/** The server lists the most recently updated requests and says there are more; one line says so. */
export const MoreThanListed: Story = {
	args: { state: { status: "ready", openWork: MANY_REVIEW_REQUESTS, login: ada.login } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Review requested 8" })).toBeVisible();
		await expect(
			canvas.getByText("Showing the 8 most recently updated of your review requests."),
		).toBeVisible();
	},
};

/** In a member's level: the same groups, named from the outside. */
export const Member: Story = {
	args: {
		perspective: "member",
		state: { status: "ready", openWork: OPEN_WORK, login: "bob" },
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { level: 3, name: "Open work" })).toBeVisible();
		await expect(canvas.getByRole("heading", { level: 4, name: "Returned 2" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /Waiting on others/u }));
		await expect(
			canvas.getByRole("heading", { level: 4, name: "Requested from their team 2" }),
		).toBeVisible();
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		providerType: "GITLAB",
		state: { status: "ready", openWork: GITLAB_OPEN_WORK, login: ada.login },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText(/^pipelines !\d+$/u).length).toBeGreaterThan(0);
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
		await expect(canvas.getByRole("heading", { name: "Assigned issues" })).toBeVisible();
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};
