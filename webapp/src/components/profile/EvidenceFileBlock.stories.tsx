import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, spyOn, userEvent } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { EvidenceFileBlock } from "./EvidenceFileBlock";

/** What the copy button puts on the clipboard, read back without a clipboard permission. */
const writeText = fn(async (_text: string) => {
	await Promise.resolve();
});

const meta = {
	component: EvidenceFileBlock,
	tags: ["autodocs"],
	parameters: {
		docs: {
			description: {
				component:
					"One quoted citation behind a result or one of its problems: its source as header, the quote beneath. A code " +
					"source is located by line and numbers its lines; an object source is named by the " +
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
	play: async ({ canvas }) => {
		const quote = canvas.getByRole("figure", { name: /ReadyAndTraceableHandoff\.java/u });
		await expect(quote).toHaveTextContent("var context = contextBuilder.build(request);");
		await expect(canvas.getByText("lines 60 to 64")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Copy passage from ReadyAndTraceableHandoff.java" }),
		).toBeVisible();
	},
};

/**
 * A citation that kept its place but not its words, as when the quote was withheld: the place is
 * named, and there is nothing to copy.
 */
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
	play: async ({ canvas }) => {
		await expect(canvas.getByText("lines 31 to 44")).toBeVisible();
		await expect(canvas.getByText("The code changes")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /^Copy passage/u })).toBeNull();
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
/**
 * A long line scrolls inside the quote instead of wrapping, so a line of code still reads as one
 * line, and the page around it keeps its width at 320px.
 */
export const LongLines: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
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
	play: async ({ canvas }) => {
		await expect(canvas.getByText("PracticeResultRepository.java")).toBeVisible();
		await expectNoPageOverflow();
		// The copy button comes first, then the quote itself, which scrolls from the keyboard.
		await userEvent.tab();
		await expect(
			canvas.getByRole("button", { name: "Copy passage from PracticeResultRepository.java" }),
		).toHaveFocus();
		await userEvent.tab();
		await expect(document.activeElement).toHaveTextContent("SELECT r.review_id");
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
 * A code citation keeps what a developer can open: the path, the lines and the side of the diff it
 * was read from. Its lines are numbered as they are in that version of the file. They are not
 * marked added: a passage of the new version can be a line the change did not touch.
 */
export const CodeSourceKeepsItsLines: Story = {
	args: {
		location: {
			path: "src/pagination.ts",
			startLine: 4,
			endLine: 4,
			sourceKind: "scm.pull-request.diff",
			side: "NEW",
			snippet: "export const PAGE_SIZE = 20;",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("pagination.ts")).toBeVisible();
		await expect(canvas.getByText("line 4")).toBeVisible();
		await expect(canvas.getByText("After")).toBeVisible();
		await expect(canvas.getByRole("figure", { name: /pagination\.ts/u })).toHaveTextContent(
			"export const PAGE_SIZE = 20;",
		);
	},
};

/** Both cited versions are shown as passages, without inferring a line diff from the quotes. */
export const OneChangedLine: Story = {
	args: {
		location: {
			path: "src/pagination.ts",
			startLine: 4,
			endLine: 4,
			sourceKind: "scm.pull-request.diff",
			change: {
				before: "export const PAGE_SIZE = 25;",
				after: "export const PAGE_SIZE = 20;",
			},
		},
	},
	beforeEach: () => {
		const clipboard = spyOn(navigator.clipboard, "writeText").mockImplementation(writeText);
		return () => clipboard.mockRestore();
	},
	play: async ({ canvas }) => {
		const quote = canvas.getByRole("figure", { name: /pagination\.ts/u });
		await expect(canvas.getByText("line 4")).toBeVisible();
		await expect(canvas.getByText("Before")).toBeVisible();
		await expect(canvas.getByText("After")).toBeVisible();
		await expect(quote).toHaveTextContent("export const PAGE_SIZE = 25;");
		await expect(quote).toHaveTextContent("export const PAGE_SIZE = 20;");
		await userEvent.click(
			canvas.getByRole("button", { name: "Copy before passage from pagination.ts" }),
		);
		await expect(writeText).toHaveBeenLastCalledWith("export const PAGE_SIZE = 25;");
		await userEvent.click(
			canvas.getByRole("button", { name: "Copy after passage from pagination.ts" }),
		);
		await expect(writeText).toHaveBeenLastCalledWith("export const PAGE_SIZE = 20;");
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

/** Shared lines remain in their quoted versions, without being labelled removed or added. */
export const SharedLinesInBothVersions: Story = {
	args: {
		location: {
			path: "src/pagination.ts",
			startLine: 10,
			endLine: 12,
			sourceKind: "scm.pull-request.diff",
			change: { before: "a\nb\nc\n", after: "a\nB\nc\n" },
		},
	},
	beforeEach: () => {
		const clipboard = spyOn(navigator.clipboard, "writeText").mockImplementation(writeText);
		return () => clipboard.mockRestore();
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Before")).toBeVisible();
		await expect(canvas.getByText("After")).toBeVisible();
		await expect(canvas.getAllByText("a", { exact: true })).toHaveLength(2);
		await expect(canvas.getAllByText("c", { exact: true })).toHaveLength(2);
		await expect(
			canvas
				.getByRole("figure", { name: /pagination\.ts/u })
				.querySelectorAll('[data-variant="removed"], [data-variant="added"]'),
		).toHaveLength(0);
		await userEvent.click(
			canvas.getByRole("button", { name: "Copy before passage from pagination.ts" }),
		);
		await expect(writeText).toHaveBeenLastCalledWith("a\nb\nc\n");
		await userEvent.click(
			canvas.getByRole("button", { name: "Copy after passage from pagination.ts" }),
		);
		await expect(writeText).toHaveBeenLastCalledWith("a\nB\nc\n");
	},
};
