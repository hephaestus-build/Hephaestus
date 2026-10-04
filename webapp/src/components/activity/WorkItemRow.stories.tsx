import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { getProviderSlug } from "@/lib/provider/provider-terms";
import {
	ada,
	approvedPullRequest,
	assignedIssue,
	bob,
	changesRequestedPullRequest,
	draftPullRequest,
	gitLabMergeRequest,
	longTitlePullRequest,
	reviewRequest,
	twoTeamsReviewRequest,
} from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { WorkItemRow } from "./WorkItemRow";

const meta = {
	component: WorkItemRow,
	decorators: [
		// A row is a list item, so it renders inside the list it belongs to, in its provider's colours.
		(Story, { args }) => (
			<ul data-provider={getProviderSlug(args.providerType)} className="rounded-xl border bg-card">
				<Story />
			</ul>
		),
		withStandardPage,
	],
	tags: ["autodocs"],
	args: { work: reviewRequest, providerType: "GITHUB", login: ada.login },
} satisfies Meta<typeof WorkItemRow>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// Someone else's work names its author; a failing check and each review are named, not only coloured.
		await expect(canvas.getByText("by Bob Brenner")).toBeVisible();
		await expect(canvas.getByText("Checks failing")).toBeVisible();
		await expect(canvas.getByRole("img", { name: "Open pull request" })).toBeVisible();
		await expect(canvas.getByRole("img", { name: "Ada Lovelace review requested" })).toBeVisible();
		await expect(canvas.getByRole("img", { name: "Chen Wei commented" })).toBeVisible();
	},
};

/** Six reviewers: four faces with where each review stands, then how many more. */
export const ManyReviewers: Story = {
	args: { work: approvedPullRequest },
	play: async ({ canvas }) => {
		const reviewers = canvas.getByRole("list", { name: "Reviewers" });
		await expect(reviewers.querySelectorAll("li")).toHaveLength(5);
		await expect(reviewers).toHaveTextContent("+2");
		await expect(
			canvas.getByRole("img", { name: "and Gus Lindqvist approved, Eli Novak review requested" }),
		).toBeVisible();
	},
};

export const ChangesRequested: Story = {
	args: { work: changesRequestedPullRequest },
	play: async ({ canvas }) => {
		// Ada's own pull request names no author.
		await expect(canvas.queryByText("Ada Lovelace")).not.toBeInTheDocument();
		await expect(canvas.getByRole("img", { name: "Bob Brenner requested changes" })).toBeVisible();
	},
};

export const Draft: Story = {
	args: { work: draftPullRequest },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "Draft pull request" })).toBeVisible();
	},
};

export const Issue: Story = {
	args: { work: assignedIssue },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "Open issue" })).toBeVisible();
	},
};

export const LongTitle: Story = {
	args: { work: longTitlePullRequest },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

/** A request to two of Ada's teams names both, and the caption wraps rather than overflows. */
export const TeamRequest: Story = {
	args: { work: twoTeamsReviewRequest },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("through payments and Billing Reliability")).toBeVisible();
		await expectNoPageOverflow();
	},
};

/** GitLab's merge request, in its own icons and colours; it is Bob's own, so it names nobody. */
export const GitLab: Story = {
	args: { work: gitLabMergeRequest, providerType: "GITLAB", login: bob.login },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "Open merge request" })).toBeVisible();
		await expect(canvas.getByText("pipelines !42")).toBeVisible();
	},
};

const onReviewNow = fn();

/** Ada's own pull request, in a workspace that reviews practices: she can ask for a review now. */
export const ReviewNow: Story = {
	args: { work: changesRequestedPullRequest, reviewNow: { onReviewNow, asking: false } },
	play: async ({ canvas, userEvent }) => {
		// The visible words come first in the name, and the name says which work it asks about.
		const button = canvas.getByRole("button", { name: "Request review: Hephaestus #2301" });
		await userEvent.click(button);
		await expect(onReviewNow).toHaveBeenCalledOnce();
	},
};

/** While the ask is on its way the button says so and takes no second press. */
export const Asking: Story = {
	args: { work: assignedIssue, reviewNow: { onReviewNow: fn(), asking: true } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Requesting review…: Hephaestus #1374" }),
		).toBeDisabled();
	},
};

/** The action wraps under the title rather than widening the page. */
export const ReviewNowReflow: Story = {
	args: { work: longTitlePullRequest, reviewNow: { onReviewNow: fn(), asking: false } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Dark: Story = { args: { work: approvedPullRequest }, globals: { theme: "dark" } };
