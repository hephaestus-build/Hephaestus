import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import type { WorkspaceSetupFacts } from "./workspace-setup";
import { WorkspaceSetupSummary } from "./WorkspaceSetupSummary";

const ready: WorkspaceSetupFacts = {
	repositories: { status: "synced", repositories: 12 },
	practiceReviews: { status: "on", automatic: true, onRequest: true },
	practices: { status: "known", chosen: 9 },
	reviewModel: { status: "known", unserved: [], memberTiers: 3 },
	feedbackMode: {
		status: "known",
		approveFirst: 9,
		automatic: 0,
		off: 0,
		defaultMode: "APPROVE_FIRST",
	},
	firstReview: { status: "done" },
};

const fresh: WorkspaceSetupFacts = {
	repositories: { status: "not-connected" },
	practiceReviews: { status: "off" },
	practices: { status: "known", chosen: 0 },
	reviewModel: {
		status: "known",
		unserved: ["IN_HOUSE", "CLOUD", "UNDECLARED"],
		memberTiers: 3,
	},
	feedbackMode: {
		status: "known",
		approveFirst: 0,
		automatic: 0,
		off: 0,
		defaultMode: "APPROVE_FIRST",
	},
	firstReview: { status: "none" },
};

/** The task-list row a title link opens from. */
function rowOf(link: HTMLElement): HTMLElement {
	const row = link.closest("li");
	if (row === null) {
		throw new Error("The link is not inside a row");
	}
	return row;
}

const meta = {
	component: WorkspaceSetupSummary,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	parameters: { chromatic: { viewports: [320, 1440] } },
	args: { workspaceSlug: "ase", state: { status: "ready", facts: ready } },
} satisfies Meta<typeof WorkspaceSetupSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Everything a member needs is in place. Each row links to the page that changes it. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Setup summary" })).toBeVisible();
		await expect(canvas.getAllByText("Done")).toHaveLength(6);
		await expect(canvas.getByRole("link", { name: "Repositories" })).toHaveAttribute(
			"href",
			"/w/ase/admin/integrations/scm",
		);
		await expect(canvas.getByRole("link", { name: "Review model" })).toHaveAttribute(
			"href",
			"/w/ase/admin/models?purpose=PRACTICE_REVIEW",
		);
		await expect(canvas.getByRole("link", { name: "Practice reviews" })).toHaveAttribute(
			"href",
			"/w/ase/admin/practices/review?section=automated-review",
		);
		await expect(canvas.getByText("12 repositories synced.")).toBeVisible();
	},
};

/** A workspace just created: nothing is set up, and the first review waits for an admin. */
export const FreshWorkspace: Story = {
	args: { state: { status: "ready", facts: fresh } },
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Repositories" }))).toHaveTextContent(
			"To do",
		);
		await expect(canvas.getByText(/^Not connected\./u)).toBeVisible();
		await expect(canvas.getByText("None chosen. Choose practices from the catalog.")).toBeVisible();
		await expect(
			canvas.getByText("No review model is ready, so no review can start."),
		).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Review recent work" })).toHaveAttribute(
			"href",
			"/w/ase/admin/practices/review?section=past-work",
		);
		await expect(canvas.getByText(/Hephaestus never starts it by itself\./u)).toBeVisible();
	},
};

/** Connected but never synced, with no sync running yet: the row says it waits, never that it syncs. */
export const WaitingForFirstSync: Story = {
	args: {
		state: {
			status: "ready",
			facts: { ...ready, repositories: { status: "waiting" }, firstReview: { status: "none" } },
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Waiting for the first sync. Work appears after it."),
		).toBeVisible();
		await expect(canvas.queryByText(/^Syncing/u)).toBeNull();
	},
};

/** The first sync is still running: the row reports the server's progress and is not done. */
export const Syncing: Story = {
	args: {
		state: {
			status: "ready",
			facts: {
				...ready,
				repositories: { status: "syncing", percent: 40 },
				firstReview: { status: "none" },
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Repositories" }))).toHaveTextContent(
			"In progress",
		);
		await expect(
			canvas.getByText("Syncing · 40%. Work appears after the first sync."),
		).toBeVisible();
	},
};

/** Practice reviews are off, so members get none however the rest is set up. */
export const ReviewsOff: Story = {
	args: { state: { status: "ready", facts: { ...ready, practiceReviews: { status: "off" } } } },
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Practice reviews" }))).toHaveTextContent(
			"To do",
		);
		await expect(canvas.getByText("Off. Members get no practice reviews.")).toBeVisible();
	},
};

/** Reviews start only when someone asks: set up, but not for every piece of work. */
export const OnRequestOnly: Story = {
	args: {
		state: {
			status: "ready",
			facts: { ...ready, practiceReviews: { status: "on", automatic: false, onRequest: true } },
		},
	},
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Practice reviews" }))).toHaveTextContent(
			"Incomplete",
		);
	},
};

/** A review model serves In-house members, but none serves Cloud members or those who have not chosen. */
export const NoModelForAChoice: Story = {
	args: {
		state: {
			status: "ready",
			facts: {
				...ready,
				reviewModel: { status: "known", unserved: ["CLOUD", "UNDECLARED"], memberTiers: 3 },
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Review model" }))).toHaveTextContent(
			"Incomplete",
		);
		await expect(canvas.getByText(/^No review model for Cloud members/u)).toBeVisible();
	},
};

/** Practices differ in feedback mode, so the row counts each. */
export const MixedFeedbackModes: Story = {
	args: {
		state: {
			status: "ready",
			facts: {
				...ready,
				feedbackMode: {
					status: "known",
					approveFirst: 6,
					automatic: 2,
					off: 1,
					defaultMode: "APPROVE_FIRST",
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Approve first for 6 practices, automatic for 2 practices and off for 1 practice.",
			),
		).toBeVisible();
	},
};

/** One fact could not be read. Its row says so and never reads as done. */
/** Connected, but a finished sync found no repository: members have no work to review yet. */
export const ConnectedWithoutRepositories: Story = {
	args: { state: { status: "ready", facts: { ...ready, repositories: { status: "empty" } } } },
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Repositories" }))).toHaveTextContent(
			"To do",
		);
		await expect(canvas.getByText("Connected, but no repository is added yet.")).toBeVisible();
	},
};

/** Reviews are on, but neither automatic reviews nor reviews on request can start one. */
export const NoReviewTrigger: Story = {
	args: {
		state: {
			status: "ready",
			facts: { ...ready, practiceReviews: { status: "on", automatic: false, onRequest: false } },
		},
	},
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Practice reviews" }))).toHaveTextContent(
			"To do",
		);
		await expect(canvas.getByText(/^On, but nothing starts a review\./u)).toBeVisible();
	},
};

/** Every practice has feedback Off: reviews record observations, but nobody gets feedback. */
export const FeedbackOff: Story = {
	args: {
		state: {
			status: "ready",
			facts: {
				...ready,
				feedbackMode: {
					status: "known",
					approveFirst: 0,
					automatic: 0,
					off: 9,
					defaultMode: "OFF",
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Feedback mode" }))).toHaveTextContent(
			"Incomplete",
		);
		await expect(canvas.getByText(/^Off\. Off keeps observations/u)).toBeVisible();
	},
};

/** Every practice sends feedback without waiting for approval. */
export const FeedbackAutomatic: Story = {
	args: {
		state: {
			status: "ready",
			facts: {
				...ready,
				feedbackMode: {
					status: "known",
					approveFirst: 0,
					automatic: 9,
					off: 0,
					defaultMode: "AUTOMATIC",
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Feedback mode" }))).toHaveTextContent(
			"Done",
		);
		await expect(
			canvas.getByText("Automatic. Feedback is sent without waiting for approval."),
		).toBeVisible();
	},
};

export const UnknownRow: Story = {
	args: {
		state: { status: "ready", facts: { ...ready, reviewModel: { status: "unknown" } } },
	},
	play: async ({ canvas }) => {
		await expect(rowOf(canvas.getByRole("link", { name: "Review model" }))).toHaveTextContent(
			"Unknown",
		);
		await expect(canvas.getByText("We could not read this.")).toBeVisible();
		await expect(canvas.getAllByText("Done")).toHaveLength(5);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Setup summary" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

/** No fact could be read at all. */
export const LoadError: Story = {
	args: {
		state: { status: "error", error: new Error("Network error"), onRetry: fn() },
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load the setup summary")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.state.status === "error") {
			await expect(args.state.onRetry).toHaveBeenCalledOnce();
		}
	},
};
