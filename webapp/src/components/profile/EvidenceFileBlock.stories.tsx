import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { EvidenceFileBlock } from "./EvidenceFileBlock";

const meta = {
	component: EvidenceFileBlock,
	tags: ["autodocs"],
	parameters: {
		docs: {
			description: {
				component:
					"One quoted citation behind a result or one of its problems: its source as header, the quote beneath. A code " +
					"source is located by line and keeps a pinned gutter; an object source is named by the " +
					"registry's words alone, because its path and numbers are offsets into the runner's own " +
					"serialised context rather than a place a reader could open.",
			},
		},
	},
	decorators: [
		(Story) => (
			<div className="max-w-lg p-4">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof EvidenceFileBlock>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		location: {
			path: "server/src/main/java/de/tum/cit/aet/hephaestus/practices/review/ReadyAndTraceableHandoff.java",
			startLine: 60,
			endLine: 64,
			sourceKind: "scm.pull-request.diff",
			snippet: [
				"  public DeliveryResult handoff(ReviewRequest request) {",
				"    var context = contextBuilder.build(request);",
				"    ReviewResult result = reviewService.evaluate(context);",
				"    return deliveryService.publish(result, request.recipient());",
				"  }",
			].join("\n"),
		},
	},
};
export const PlaceWithoutQuote: Story = {
	args: {
		location: {
			path: "docs/contributor/practice-catalog.md",
			startLine: 31,
			endLine: 44,
			sourceKind: "scm.pull-request.diff",
		},
	},
	render: (args) => (
		<div className="flex flex-col gap-3">
			<EvidenceFileBlock {...args} />
			<EvidenceFileBlock location={{ sourceKind: "scm.pull-request.diff" }} />
		</div>
	),
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("lines 31 to 44")).toBeVisible();
		await expect(canvas.getByText("The code changes")).toBeVisible();
		await expect(canvasElement.querySelector("pre")).toBeNull();
	},
};
export const BareFileName: Story = {
	args: {
		location: {
			path: "CHANGELOG.md",
			startLine: 1,
			endLine: 1,
			sourceKind: "scm.pull-request.diff",
			snippet: "## 0.14.0",
		},
	},
};
export const LongLines: Story = {
	args: {
		location: {
			path: "server/src/main/java/de/tum/cit/aet/hephaestus/practices/result/PracticeResultRepository.java",
			startLine: 1,
			endLine: 2,
			sourceKind: "scm.pull-request.diff",
			snippet: [
				'SELECT r.review_id AS "reviewId", MAX(r.recorded_at) AS "reviewedAt" FROM result r JOIN practice p ON p.id = r.practice_id JOIN practice_group g ON g.id = p.practice_group_id',
				"WHERE r.developer_id = :developerId AND p.workspace_id = :workspaceId AND g.slug = :groupSlug AND r.outcome <> 'DOES_NOT_APPLY'",
			].join("\n"),
		},
	},
};
/**
 * A quote from the pull request itself. Its citation carries `inputs/context/metadata.json`,
 * line 9 — the runner's own file — so the caption names the source instead and shows no numbers.
 */
export const ObjectSourceNamesTheSource: Story = {
	args: {
		location: {
			path: "inputs/context/metadata.json",
			startLine: 9,
			endLine: 9,
			sourceKind: "scm.pull-request.core",
			snippet: '"body" : ""',
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The reviewed work itself")).toBeVisible();
		await expect(canvas.queryByText("inputs/context/metadata.json")).toBeNull();
		await expect(canvas.queryByText("line 9")).toBeNull();
		await expect(canvas.getByText('"body" : ""')).toBeVisible();
	},
};

/**
 * A code citation keeps what a developer can open: the path and the lines in the caption, and the
 * side of the diff on the quoted line itself.
 */
export const CodeSourceKeepsItsLines: Story = {
	args: {
		location: {
			path: "src/pagination.ts",
			startLine: 4,
			endLine: 4,
			sourceKind: "scm.pull-request.diff",
			side: "NEW",
			snippet: "+export const PAGE_SIZE = 20;",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("pagination.ts")).toBeVisible();
		await expect(canvas.getByText("line 4")).toBeVisible();
		// The side is the gutter of the line it covers, inside the quote and not above it.
		const side = canvas.getByText("After");
		await expect(side).toBeVisible();
		await expect(side.closest("figcaption")).toBeNull();
	},
};

export const OneChangedLine: Story = {
	args: {
		location: {
			path: "src/pagination.ts",
			startLine: 4,
			endLine: 4,
			sourceKind: "scm.pull-request.diff",
			change: {
				before: "-export const PAGE_SIZE = 25;",
				after: "+export const PAGE_SIZE = 20;",
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("pagination.ts")).toBeVisible();
		// The file is named once, and neither a line range nor a side word is up there: a range over
		// two sides would be true of one of them.
		await expect(canvas.queryByText("line 4")).toBeNull();
		const caption = canvas.getByText("pagination.ts").closest("figcaption");
		if (!caption) {
			throw new Error("The path is the block's caption");
		}
		await expect(within(caption).queryByText("Before")).toBeNull();
		await expect(within(caption).queryByText("After")).toBeNull();

		// Each side is the gutter of its own row.
		const removed = canvas.getByText("-export const PAGE_SIZE = 25;");
		const added = canvas.getByText("+export const PAGE_SIZE = 20;");
		const removedRow = removed.closest<HTMLElement>("span.grid");
		const addedRow = added.closest<HTMLElement>("span.grid");
		if (!removedRow || !addedRow) {
			throw new Error("Every quoted line is a row of the quote");
		}
		await expect(within(removedRow).getByText("Before")).toBeVisible();
		await expect(within(addedRow).getByText("After")).toBeVisible();
	},
};

/** Only the old side was cited, so the gutter says which side it is, once. */
export const QuotedFromBeforeTheChange: Story = {
	args: {
		location: {
			path: "webapp/src/components/profile/ReviewCard.tsx",
			startLine: 23,
			endLine: 24,
			sourceKind: "scm.pull-request.diff",
			side: "OLD",
			snippet: [
				'if (kind === "PULL_REQUEST") return GitPullRequestIcon;',
				"return undefined;",
			].join("\n"),
		},
	},
};

/**
 * Two quotes of one object source, folded into one block: the pull request itself is named once
 * and each quote takes its own line. Two blocks under the same caption would read as two pieces
 * of evidence when only one thing was quoted twice.
 */
export const FoldedObjectSource: Story = {
	args: {
		location: {
			path: "inputs/context/metadata.json",
			startLine: 4,
			endLine: 4,
			sourceKind: "scm.pull-request.core",
			snippet: ['"title" : "Page size"', '"body" : ""'].join("\n"),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("The reviewed work itself")).toHaveLength(1);
		await expect(canvas.getByText('"title" : "Page size"')).toBeVisible();
		await expect(canvas.getByText('"body" : ""')).toBeVisible();
		// An object locator points into the runner's own file, so no line number is shown for either.
		await expect(canvas.queryByText("line 4")).toBeNull();
	},
};

/**
 * A quote from the repository's history rather than the reviewed diff: the caption also names the
 * commit it was verified against, since the lines may no longer read so at the reviewed one.
 */
export const HistoricalSource: Story = {
	args: {
		location: {
			path: "src/service.ts",
			startLine: 12,
			endLine: 12,
			sourceKind: "scm.repository.tree",
			commit: "b".repeat(40),
			snippet: "return previousValue;",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("service.ts")).toBeVisible();
		await expect(canvas.getByText("line 12")).toBeVisible();
		await expect(canvas.getByText("b".repeat(40))).toBeVisible();
	},
};
