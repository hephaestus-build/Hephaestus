import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { artifactTrace, untouchedArtifactTrace } from "@/components/practice-trace/fixtures";
import { TraceRefusalAlert } from "@/components/practice-trace/TraceRefusalAlert";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import { groups } from "@/stories/practice-profile-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";

import {
	outlineDocument,
	reviewArtifact,
	reviewFeedback,
	reviewObservations,
	type ReviewWork,
	workspacePractices,
} from "./fixtures";
import { workLevel } from "./review-levels";
import { REVIEW_PREVIEW_SIZE } from "./review-search";
import type { ReviewSectionState } from "./review-states";
import { ReviewedWorkLevel, type ReviewedWorkTraceState } from "./ReviewedWorkLevel";

/**
 * The level shows one work's review output, so a story names the work and takes the rows the
 * endpoints would return for it: hand-picked rows can put one work's observation on another's level.
 * The section shows a preview and links on to the full list, so `total` is the whole count while
 * `items` is only the first page of it.
 */
const outputFor = <T extends { reviewedWork?: { id: string } }>(
	rows: T[],
	{ reviewedWork }: ReviewWork,
): ReviewSectionState<T> => {
	const matching = rows.filter((row) => row.reviewedWork?.id === reviewedWork.id);
	return {
		status: "ready",
		items: matching.slice(0, REVIEW_PREVIEW_SIZE),
		total: matching.length,
	};
};

const empty = <T,>(): ReviewSectionState<T> => ({ status: "ready", items: [], total: 0 });

/** The level the stack opens; which work it names is the args' business, not the stack's. */
const WORK_LEVEL = workLevel(reviewArtifact.reviewedWork.kind, reviewArtifact.reviewedWork.id);
if (!WORK_LEVEL) {
	throw new Error("A pull request can always be named as a level");
}

/** What was recorded about the work, named as the rows name it. */
const traceFor = ({ reviewedWork }: ReviewWork): ReviewedWorkTraceState => ({
	status: "ready",
	trace: { ...artifactTrace, artifactId: Number(reviewedWork.id), reviewedWork },
});

const argsFor = (work: ReviewWork) => ({
	artifactKind: work.reviewedWork.kind,
	artifactId: Number(work.reviewedWork.id),
	feedback: outputFor(reviewFeedback, work),
	observations: outputFor(reviewObservations, work),
	trace: traceFor(work),
});

/**
 * Everything recorded about one piece of work, across every review of it, opened over the record
 * that named the work: what the reviews said, every practice's answer, and what was noticed.
 */
const meta = {
	component: ReviewedWorkLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		path: {
			behind: [
				{ label: "Practice reviews", depth: 0 },
				{ label: "Observation", depth: 1 },
			],
			onClose: fn(),
		},
		...argsFor(reviewArtifact),
		practices: workspacePractices,
		groups,
		onOpenPractice: fn(),
		onReviewNow: fn(),
	},
	argTypes: { path: { control: false }, refusal: { control: false } },
	render: (args) => (
		<InLevelStack entry={WORK_LEVEL} path={args.path} size="detailWide">
			{(level) => <ReviewedWorkLevel {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof ReviewedWorkLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PullRequest: Story = {
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		// The heading is the work's own title, as the server names it; the work is the link under it,
		// named the way its provider writes it.
		await expect(
			panel.getByRole("heading", { name: reviewArtifact.title, level: 2 }),
		).toBeVisible();
		await expect(
			panel.getByRole("link", { name: /^Pull request #1423 · ls1intum\/Hephaestus/u }),
		).toHaveAttribute("href", reviewArtifact.reviewedWork.url);
		await expect(panel.getByRole("heading", { name: "Observations", level: 3 })).toBeVisible();
		// The trace's counts reach the tabs before either is opened.
		panel.getByRole("tab", { name: "Every practice 12" });
		panel.getByRole("tab", { name: "What we noticed 5" });
		await userEvent.click(panel.getByRole("button", { name: "Request review" }));
		await expect(args.onReviewNow).toHaveBeenCalledTimes(1);
		// The path names both levels behind this one, and a crumb closes down to its own.
		await userEvent.click(panel.getByRole("button", { name: "Observation" }));
		await expect(args.path.onClose).toHaveBeenCalledWith(1);
	},
};

/**
 * Every practice's latest answer, the quiet ones included, with the operating facts an admin reads
 * under each; "Rests on" opens the occurrence on the timeline.
 */
export const EveryPractice: Story = {
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("tab", { name: "Every practice 12" }));
		const table = panel.getByRole("table", { name: "Every practice on this work" });
		// A practice no review speaks for is still a row, with its reason.
		within(table).getByText("Discussion hygiene");
		within(table).getByText("Waiting on a connection");
		await userEvent.click(within(table).getByRole("button", { name: "Thin controllers" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("thin-controllers");

		const [restsOn] = within(table).getAllByRole("button", {
			name: "Rests on Marked ready for review",
		});
		if (!restsOn) {
			throw new Error("No practice rests on the ready occurrence");
		}
		await userEvent.click(restsOn);
		await expect(panel.getByRole("tab", { name: "What we noticed 5" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(panel.getByText("Marked ready for review").closest("li")).toHaveFocus();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

/**
 * No review ever said anything about this work, so the trace names it, the same way a review would.
 * The level opens on what was noticed, which is where the reason is.
 */
export const NothingReviewed: Story = {
	args: {
		artifactId: 45,
		feedback: empty(),
		observations: empty(),
		trace: {
			status: "ready",
			trace: {
				...untouchedArtifactTrace,
				artifactKind: reviewArtifact.reviewedWork.kind,
				artifactId: 45,
				reviewedWork: {
					...reviewArtifact.reviewedWork,
					id: "45",
					label: "#45",
					title: "Bump the webhook signature library",
					url: "https://github.com/ls1intum/Hephaestus/pull/45",
				},
			},
		},
	},
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("tab", { name: /^What we noticed/u })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(panel.getByRole("heading", { name: "What we noticed" })).toBeVisible();
		await userEvent.click(panel.getByRole("tab", { name: "Observations and feedback" }));
		await expect(panel.getByText("Nothing has been reviewed on this work")).toBeVisible();
		await expect(
			panel.getByRole("heading", { name: "Bump the webhook signature library", level: 2 }),
		).toBeVisible();
		await expect(
			panel.getByRole("link", { name: /^Pull request #45 · ls1intum\/Hephaestus/u }),
		).toHaveAttribute("href", "https://github.com/ls1intum/Hephaestus/pull/45");
		await expect(panel.queryByRole("heading", { name: "Observations" })).not.toBeInTheDocument();
	},
};

/**
 * Nothing about the work was ever recorded — work from before Hephaestus kept that record — which is
 * an answer, not a failure: every tab says there is nothing, and none offers a retry.
 */
export const NothingRecorded: Story = {
	args: { feedback: empty(), observations: empty(), trace: { status: "none" } },
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Nothing has been reviewed on this work")).toBeVisible();
		panel.getByText(/nothing else was recorded about it either/u);
		await userEvent.click(panel.getByRole("tab", { name: "Every practice" }));
		await expect(panel.getByText("No practice was asked about this work")).toBeVisible();
		await userEvent.click(panel.getByRole("tab", { name: "What we noticed" }));
		await expect(panel.getByText("Nothing was recorded about this work")).toBeVisible();
		await expect(panel.queryByText(/We could not load/u)).not.toBeInTheDocument();
		await expect(panel.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};

/** Nothing has answered yet: the work's link is a skeleton rather than a guess at its name. */
export const Loading: Story = {
	args: {
		feedback: { status: "loading" },
		observations: { status: "loading" },
		trace: { status: "loading" },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.queryByText("Nothing has been reviewed on this work"),
		).not.toBeInTheDocument();
		panel.getByText("Loading feedback");
		panel.getByText("Loading observations");
		await expect(panel.queryByRole("link", { name: /#1423/u })).not.toBeInTheDocument();
		// A count still on its way is left out rather than drawn as zero.
		panel.getByRole("tab", { name: "Every practice" });
	},
};

/** The trace is still on its way; what the reviews said is already here. */
export const TraceLoading: Story = {
	args: { trace: { status: "loading" } },
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("link", { name: /^Pull request #1423 · ls1intum\/Hephaestus/u });
		await userEvent.click(panel.getByRole("tab", { name: "Every practice" }));
		await expect(panel.getByRole("table", { name: "Every practice on this work" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

/** A failed trace costs its two tabs and nothing else, and says so rather than showing none. */
export const TraceFailed: Story = {
	args: { trace: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { name: "Observations", level: 3 })).toBeVisible();
		await userEvent.click(panel.getByRole("tab", { name: "What we noticed" }));
		await expect(
			panel.getByText("We could not load what was recorded about this work"),
		).toBeVisible();
		await userEvent.click(panel.getByRole("button", { name: "Retry" }));
		if (args.trace.status !== "error") {
			throw new Error("The story's trace is not the failed one");
		}
		await expect(args.trace.onRetry).toHaveBeenCalledTimes(1);
	},
};

/** The ask started nothing: the level says why, in the server's words, with the fix an admin applies. */
export const Refused: Story = {
	args: {
		refusal: (
			<TraceRefusalAlert
				refusal={{
					status: "REFUSED",
					reason: "BUDGET_EXHAUSTED",
					reasonDescription: "The workspace’s AI budget for this month is used up.",
				}}
				workspaceSlug="demo"
				canAdminister
			/>
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("No review was started")).toBeVisible();
		panel.getByText("The workspace’s AI budget for this month is used up.");
		panel.getByRole("link", { name: "Open AI usage" });
	},
};

/** The ask is in flight: the one button says so and cannot be pressed twice. */
export const Asking: Story = {
	args: { requesting: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("button", { name: "Requesting review…" })).toBeDisabled();
	},
};

/** A document is reviewed when its source publishes it; nobody can ask for one by hand. */
export const DocumentCannotBeAsked: Story = {
	args: argsFor(outlineDocument),
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.getByRole("heading", { name: outlineDocument.title, level: 2 }),
		).toBeVisible();
		await expect(panel.queryByRole("button", { name: "Request review" })).not.toBeInTheDocument();
	},
};

/**
 * One section failing does not take the other down with it, and — the reason the empty state is
 * gated on both — a failure is never read as "nothing was found".
 */
export const ObservationsFailed: Story = {
	args: {
		observations: { status: "error", error: { status: 500 }, onRetry: fn() },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("link", { name: /^Pull request #1423 · ls1intum\/Hephaestus/u });
		await expect(panel.getByText("We could not load observations")).toBeVisible();
		await expect(
			panel.queryByText("Nothing has been reviewed on this work"),
		).not.toBeInTheDocument();
	},
};
