import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type { MemberReviewReadiness } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { WorkProviderContext } from "@/lib/work-provider-context";
import { pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { minutesBefore } from "@/stories/story-clock";

import { ProfileFirstRun } from "./ProfileFirstRun";

const READY: MemberReviewReadiness = {
	blocker: null,
	events: [
		"scm.pull_request.opened",
		"scm.pull_request.ready",
		"scm.pull_request_review.submitted",
	],
	approvalBeforePosting: false,
};

const blocked = (blocker: MemberReviewReadiness["blocker"]): MemberReviewReadiness => ({
	...READY,
	blocker,
});

/**
 * What the Practice profile says while the reader has no feedback, chosen from the server's word:
 * why no review can run, a first review under way, or what starts one.
 */
const meta = {
	component: ProfileFirstRun,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaceName: "Intro course",
		readiness: READY,
		reviewed: false,
		aiChoiceLink: (label: string) => (
			<Button variant="outline" size="sm">
				{label}
			</Button>
		),
	},
} satisfies Meta<typeof ProfileFirstRun>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Day one: what can start a review and where its result appears, with the way to the practices. It
 * promises no review, because the repository and each practice still decide.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No review of your work yet")).toBeVisible();
		await expect(canvas.getByText("A review of your work can start when:")).toBeVisible();
		await expect(canvas.getByText(/ready for review$/u)).toBeVisible();
		await expect(canvas.getByText(/also depends on the repository/u)).toBeVisible();
		await expect(canvas.getByText("you submit a review")).toBeVisible();
		await expect(
			canvas.getByRole("link", { name: "See what this workspace reviews" }),
		).toBeVisible();
		await expect(canvas.queryByText(/approves feedback/u)).toBeNull();
	},
};

/** Where an admin approves feedback first, the page says so about the workspace, never about an item. */
export const ApprovalBeforePosting: Story = {
	args: { readiness: { ...READY, approvalBeforePosting: true } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"In Intro course, a workspace admin approves feedback before it is posted on your work.",
			),
		).toBeVisible();
	},
};

/** An event this build does not know says nothing, rather than its wire name. */
export const UnknownEventsOnly: Story = {
	args: { readiness: { ...READY, events: ["scm.release.published"] } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("A review starts when:")).toBeNull();
		await expect(canvas.queryByText(/scm\./u)).toBeNull();
	},
};

export const FirstReviewWaiting: Story = {
	args: {
		activeReview: {
			reviewId: "7a1e2a3b-0000-4000-8000-000000000101",
			at: minutesBefore(3),
			item: pullRequest(12),
			status: "QUEUED",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Your first review is waiting to start")).toBeVisible();
		await expect(canvas.getByText(/waits for capacity/u)).toBeVisible();
	},
};

export const FirstReviewRunning: Story = {
	args: {
		activeReview: {
			reviewId: "7a1e2a3b-0000-4000-8000-000000000102",
			at: minutesBefore(1),
			item: pullRequest(12),
			status: "RUNNING",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Your first review is running")).toBeVisible();
		await expect(canvas.getByText(/appears here when it finishes/u)).toBeVisible();
		await expect(canvas.getByRole("link", { name: /#12/u })).toHaveAttribute(
			"href",
			pullRequest(12).url ?? "",
		);
	},
};

/**
 * A review someone asked for runs in a workspace that starts none by itself: the review under way
 * speaks, not the reason no automatic review starts.
 */
/** While a first review runs where an admin approves feedback first, the page says so. */
export const FirstReviewRunningWithApproval: Story = {
	args: {
		readiness: { ...READY, approvalBeforePosting: true },
		activeReview: {
			reviewId: "7a1e2a3b-0000-4000-8000-000000000104",
			at: minutesBefore(1),
			item: pullRequest(12),
			status: "RUNNING",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Your first review is running")).toBeVisible();
		await expect(canvas.getByText(/a workspace admin approves feedback/u)).toBeVisible();
	},
};

/** Reviews on request can still end in feedback that waits for approval. */
export const OnRequestOnlyWithApproval: Story = {
	args: { readiness: { ...blocked("ON_REQUEST_ONLY"), approvalBeforePosting: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reviews here start only on request")).toBeVisible();
		await expect(canvas.getByText(/a workspace admin approves feedback/u)).toBeVisible();
	},
};

export const FirstReviewRunningOnRequestOnly: Story = {
	args: {
		readiness: blocked("ON_REQUEST_ONLY"),
		activeReview: {
			reviewId: "7a1e2a3b-0000-4000-8000-000000000103",
			at: minutesBefore(1),
			item: pullRequest(12),
			status: "RUNNING",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Your first review is running")).toBeVisible();
		await expect(canvas.queryByText("Reviews here start only on request")).toBeNull();
	},
};

/** A member the workspace cannot match to their work is not told that an admin left them out. */
export const NotEligible: Story = {
	args: { readiness: blocked("NOT_ELIGIBLE") },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Hephaestus cannot match your work to you here yet"),
		).toBeVisible();
		await expect(canvas.queryByText(/admin chose/u)).toBeNull();
	},
};

/** On GitLab, the events name merge requests. */
export const GitLabWork: Story = {
	decorators: [
		(Story) => (
			<WorkProviderContext value="GITLAB">
				<Story />
			</WorkProviderContext>
		),
	],
	play: async ({ canvas }) => {
		await expect(canvas.getByText("you open a merge request")).toBeVisible();
	},
};

/** A long workspace name and the event list wrap at 320 px without widening the page. */
export const MobileReflow: Story = {
	args: {
		workspaceName: "Interdisciplinary software engineering project course, winter term",
		readiness: { ...READY, approvalBeforePosting: true },
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};

/** A reason the reader can change in their AI choice comes with the way there. */
export const NoAi: Story = {
	args: { readiness: blocked("NO_AI") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hephaestus does not review your work here")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Change your AI choice" })).toBeVisible();
	},
};

export const ChoiceRequired: Story = {
	args: { readiness: blocked("CHOICE_REQUIRED") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Choose your AI to get reviews")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Choose your AI" })).toBeVisible();
	},
};

export const NoModelForChoice: Story = {
	args: { readiness: blocked("NO_MODEL") },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Practice reviews are not set up for your AI choice yet"),
		).toBeVisible();
	},
};

/** A read-only user view shows the reason without a way to change someone else's choice. */
export const NoAiInUserView: Story = {
	args: { readiness: blocked("NO_AI"), aiChoiceLink: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hephaestus does not review your work here")).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

/** A reason only a workspace admin can change names them and offers no button. */
export const ReviewsOff: Story = {
	args: { readiness: blocked("REVIEWS_OFF") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Practice reviews are off here")).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

/** Where reviews run only on request, the way to ask is the next step. */
export const OnRequestOnly: Story = {
	args: {
		readiness: blocked("ON_REQUEST_ONLY"),
		requestReviewLink: <a href="#activity">Request a review on Activity</a>,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reviews here start only on request")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Request a review on Activity" })).toBeVisible();
	},
};

export const OutsideReviewScope: Story = {
	args: { readiness: blocked("OUTSIDE_REVIEW_SCOPE") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Your work is outside the review scope")).toBeVisible();
	},
};

export const NoPractice: Story = {
	args: { readiness: blocked("NO_PRACTICE") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practice here reviews your work yet")).toBeVisible();
	},
};

export const WorkspaceInactive: Story = {
	args: { readiness: blocked("WORKSPACE_INACTIVE") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reviews are paused in this workspace")).toBeVisible();
	},
};

/** Once a review has recorded something, the page's own empty tab speaks instead. */
export const AfterFirstReview: Story = {
	args: { reviewed: true },
	play: async ({ canvasElement }) => {
		await expect(canvasElement.textContent.trim()).toBe("");
	},
};
