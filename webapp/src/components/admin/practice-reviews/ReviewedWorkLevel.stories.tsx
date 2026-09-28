import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";

import {
	reviewArtifact,
	reviewFeedback,
	reviewObservations,
	type ReviewWork,
	workspacePractices,
} from "./fixtures";
import { workLevel } from "./review-levels";
import { REVIEW_PREVIEW_SIZE } from "./review-search";
import type { ReviewSectionState } from "./review-states";
import { ReviewedWorkLevel } from "./ReviewedWorkLevel";

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

const argsFor = (work: ReviewWork) => ({
	artifactKind: work.reviewedWork.kind,
	artifactId: Number(work.reviewedWork.id),
	feedback: outputFor(reviewFeedback, work),
	observations: outputFor(reviewObservations, work),
});

/**
 * Everything the reviews have said about one piece of work, across every review of it, opened over
 * the record that named the work.
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
	},
	argTypes: { path: { control: false } },
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
		// named as its rows name it.
		await expect(
			panel.getByRole("heading", { name: reviewArtifact.title, level: 2 }),
		).toBeVisible();
		await expect(
			panel.getByRole("link", { name: /ls1intum\/Hephaestus · #1423/u }),
		).toHaveAttribute("href", reviewArtifact.reviewedWork.url);
		await expect(panel.getByRole("heading", { name: "Observations", level: 3 })).toBeVisible();
		// The path names both levels behind this one, and a crumb closes down to its own.
		await userEvent.click(panel.getByRole("button", { name: "Observation" }));
		await expect(args.path.onClose).toHaveBeenCalledWith(1);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

/**
 * Both sections answered and both are empty. Nothing here names the work, because nothing that ever
 * looked at it came back — the level knows an id and a kind, and says only that much.
 */
export const NothingReviewed: Story = {
	args: { artifactId: 45, feedback: empty(), observations: empty() },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Nothing has been reviewed on this work")).toBeVisible();
		await expect(panel.getByRole("heading", { level: 2 })).toHaveTextContent(
			"Pull or merge request",
		);
		await expect(panel.queryByRole("heading", { name: "Observations" })).not.toBeInTheDocument();
	},
};

/** Neither section has answered yet: the work's link is a skeleton rather than a guess at its name. */
export const Loading: Story = {
	args: { feedback: { status: "loading" }, observations: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.queryByText("Nothing has been reviewed on this work"),
		).not.toBeInTheDocument();
		panel.getByText("Loading feedback");
		panel.getByText("Loading observations");
		await expect(panel.queryByRole("link", { name: /#1423/u })).not.toBeInTheDocument();
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
		panel.getByRole("link", { name: /ls1intum\/Hephaestus · #1423/u });
		await expect(panel.getByText("Couldn't load observations")).toBeVisible();
		await expect(
			panel.queryByText("Nothing has been reviewed on this work"),
		).not.toBeInTheDocument();
	},
};
