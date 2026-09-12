import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
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
					"source is located by line and keeps a pinned gutter; an object source is not, because its " +
					"numbers are offsets into a serialised file rather than places a reader could open.",
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
export const Collapsed: Story = {
	args: {
		defaultOpen: false,
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

export const HistoricalSource: Story = {
	args: {
		location: {
			path: "src/service.ts",
			startLine: 12,
			endLine: 12,
			sourceKind: "scm.repository.tree",
			revision: "b".repeat(40),
			snippet: "return previousValue;",
			redacted: false,
		},
	},
	play: async ({ canvas }) => {
		const revision = canvas.getByText("b".repeat(7));
		await expect(revision).toBeVisible();
		await expect(revision.closest("[title]")).toHaveAttribute("title", "b".repeat(40));
	},
};
