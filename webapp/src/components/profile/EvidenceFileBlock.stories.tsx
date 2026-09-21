import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { EvidenceFileBlock } from "./EvidenceFileBlock";

const meta = {
	component: EvidenceFileBlock,
	tags: ["autodocs"],
	title: "Profile/EvidenceFileBlock",
	parameters: {
		docs: {
			description: {
				component:
					"One quoted citation behind an observation: its source as header, the quote beneath. A code " +
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
			redacted: false,
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
export const SingleLine: Story = {
	args: {
		location: {
			path: "webapp/src/routes/_authenticated/w/$workspaceSlug/user/$username/index.tsx",
			startLine: 118,
			endLine: 118,
			sourceKind: "scm.pull-request.diff",
			redacted: false,
			snippet:
				"  const statusesQuery = useQuery(listPracticeGroupStandingsOptions({ path: { workspaceSlug } }));",
		},
	},
};
/** The quote is the block: there is nothing to unfold and no control that could hide it. */
export const AlwaysQuoted: Story = {
	args: {
		location: {
			path: "server/src/main/resources/db/changelog/1786939608194_changelog.xml",
			startLine: 12,
			endLine: 14,
			sourceKind: "scm.pull-request.diff",
			redacted: false,
			snippet:
				'<changeSet id="1786939608194-1" author="hephaestus">\n  <addColumn tableName="observation" />\n</changeSet>',
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/addColumn/)).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};
export const Redacted: Story = {
	args: {
		location: {
			path: "docs/contributor/practice-catalogue.md",
			startLine: 31,
			endLine: 44,
			sourceKind: "scm.pull-request.diff",
			redacted: true,
		},
	},
};
export const RedactedBySecretScanner: Story = {
	args: {
		location: {
			path: "server/application/src/main/resources/application-local.yml",
			startLine: 12,
			endLine: 12,
			sourceKind: "scm.pull-request.diff",
			side: "NEW",
			redacted: true,
		},
		detector: "secret-diff-scanner",
	},
};
export const BareFileName: Story = {
	args: {
		location: {
			path: "CHANGELOG.md",
			startLine: 1,
			endLine: 1,
			sourceKind: "scm.pull-request.diff",
			redacted: false,
			snippet: "## 0.14.0",
		},
	},
};
export const LongLines: Story = {
	args: {
		location: {
			path: "server/src/main/java/de/tum/cit/aet/hephaestus/practices/observation/ObservationRepository.java",
			startLine: 1,
			endLine: 2,
			sourceKind: "scm.pull-request.diff",
			redacted: false,
			snippet: [
				'SELECT o.agent_job_id AS "jobId", MAX(o.observed_at) AS "reviewedAt" FROM observation o JOIN practice p ON p.id = o.practice_id JOIN practice_group g ON g.id = p.practice_group_id',
				"WHERE o.about_user_id = :aboutUserId AND p.workspace_id = :workspaceId AND g.slug = :groupSlug AND o.presence <> 'NOT_APPLICABLE'",
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
			redacted: false,
			snippet: '"body" : ""',
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The pull request itself")).toBeVisible();
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
			redacted: false,
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

/**
 * One changed line, cited from both sides of the diff: one block naming the file and the line
 * once, with what the line was above what it became.
 */
export const OneChangedLine: Story = {
	args: {
		location: {
			path: "src/pagination.ts",
			startLine: 4,
			endLine: 4,
			sourceKind: "scm.pull-request.diff",
			redacted: false,
			change: {
				before: "-export const PAGE_SIZE = 25;",
				after: "+export const PAGE_SIZE = 20;",
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("pagination.ts")).toBeVisible();
		// The file and its line are named once, in the caption, and neither side word is up there.
		await expect(canvas.getAllByText("line 4")).toHaveLength(1);
		const caption = canvas.getByText("pagination.ts").closest("figcaption");
		if (!caption) throw new Error("The path is the block's caption");
		await expect(within(caption).queryByText("Before")).toBeNull();
		await expect(within(caption).queryByText("After")).toBeNull();

		// Each side is the gutter of its own row, and the line beside it carries that side's wash.
		const removed = canvas.getByText("-export const PAGE_SIZE = 25;");
		const added = canvas.getByText("+export const PAGE_SIZE = 20;");
		await expect(removed).toHaveClass("bg-destructive/10");
		await expect(added).toHaveClass("bg-success/10");
		const removedRow = removed.closest<HTMLElement>("span.grid");
		const addedRow = added.closest<HTMLElement>("span.grid");
		if (!removedRow || !addedRow) throw new Error("Every quoted line is a row of the quote");
		await expect(within(removedRow).getByText("Before")).toBeVisible();
		await expect(within(addedRow).getByText("After")).toBeVisible();
	},
};

export const ObjectSource: Story = {
	args: {
		location: {
			path: "conversation_thread.json",
			startLine: 62,
			endLine: 70,
			sourceKind: "slack.conversation.thread",
			redacted: false,
			snippet: [
				"@marta: are we rolling this out behind the flag, or straight to everyone?",
				"@jon: behind the flag — I want a day of telemetry before we widen it.",
				"@marta: works for me. I'll write the rollback step into the runbook.",
			].join("\n"),
		},
	},
};
/** Only the old side was cited, so the gutter says which side it is, once. */
export const QuotedFromBeforeTheChange: Story = {
	args: {
		location: {
			path: "webapp/src/components/profile/ReviewRunCard.tsx",
			startLine: 23,
			endLine: 24,
			sourceKind: "scm.pull-request.diff",
			side: "OLD",
			redacted: false,
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
			redacted: false,
			snippet: ['"title" : "Page size"', '"body" : ""'].join("\n"),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("The pull request itself")).toHaveLength(1);
		await expect(canvas.getByText('"title" : "Page size"')).toBeVisible();
		await expect(canvas.getByText('"body" : ""')).toBeVisible();
		// An object locator points into the runner's own file, so no line number is shown for either.
		await expect(canvas.queryByText("line 4")).toBeNull();
	},
};
