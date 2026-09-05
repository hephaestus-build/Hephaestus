import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type { EvidenceCitation } from "@/api/types.gen";
import { knownEvidenceSourceKinds } from "@/components/practice-vocabulary/evidence-source-defs";
import { expectNoPageOverflow } from "@/test/reflow";

import { ObservationEvidence } from "./ObservationEvidence";

/**
 * `path` is what each kind actually puts there: a repo-relative file for the code sources, and a
 * human-facing object name for the rest — the server's own instruction is that a non-diff citation
 * "names the issue or comment object, not a fabricated source file".
 */
const CITATION_BY_KIND: Record<string, { path: string; quote: string }> = {
	"scm.pull-request.core": {
		path: "Pull request description",
		quote: "Adds the admin read surface for review output. Follow-up to #1402.",
	},
	"scm.pull-request.diff": {
		path: "webapp/src/components/admin/practice-reviews/ReviewRow.tsx",
		quote:
			'const routeName = "detection-output";\nreturn <Link to={routeName}>{finding.title}</Link>;',
	},
	"scm.pull-request.comments": {
		path: "Comment by @grace",
		quote: "Can we name this after what an operator is looking for rather than the pipeline stage?",
	},
	"scm.repository.tree": {
		path: "webapp/src/lib/artifact-kinds.ts",
		quote: 'export const ARTIFACT_KIND = {\n\tpullRequest: "scm.pull_request",\n};',
	},
	"scm.issue.core": {
		path: "Issue #1430",
		quote: "Operators cannot tell which reviews produced feedback and which were withheld.",
	},
	"scm.issue.comments": {
		path: "Comment by @ada",
		quote: "The wording here still says findings — we agreed on observations.",
	},
	"docs.document.core": {
		path: "Reviewing practices",
		quote: "Feedback is addressed to the author, in the second person, and names one change.",
	},
	"slack.conversation.thread": {
		path: "Message from Ada Lovelace",
		quote: "I renamed the route but left the old one redirecting, so nobody's bookmark breaks.",
	},
	"scm.linked-work-items": {
		path: "Issue #1402",
		quote: "Blocked by: the read model must expose the withholding reason.",
	},
	"scm.review-threads": {
		path: "Thread on ReviewRow.tsx",
		quote: "This row is doing two jobs — can the table and the card be one component?",
	},
	"scm.general-review-comments": {
		path: "Review by @grace",
		quote: "Approving once the terminology sweep lands.",
	},
	"scm.pull-request.commits": {
		path: "Commit 3f2a9c1",
		quote: "Rename the route to observations and keep the old path redirecting",
	},
	"workspace.project-inventory": {
		path: "webapp",
		quote: "React 19, TanStack Router, Tailwind 4, Vitest, Storybook 10.",
	},
	"outline.documents": {
		path: "Frontend conventions",
		quote: "Prefer one responsive component over a table and a card list.",
	},
	"hephaestus.observation-history": {
		path: "Observation from 3 July",
		quote: "The route exposed an internal pipeline term; raised then, and unchanged since.",
	},
	"hephaestus.feedback-history": {
		path: "Feedback sent on 3 July",
		quote: "You named the boundary after the storage model rather than the product concept.",
	},
};

function citation(sourceKind: string, overrides: Partial<EvidenceCitation> = {}): EvidenceCitation {
	const sample = CITATION_BY_KIND[sourceKind];
	if (!sample) throw new Error(`No sample passage is written for evidence source ${sourceKind}`);
	const { path, quote } = sample;
	const isDiff = sourceKind === "scm.pull-request.diff";
	return {
		sourceKind,
		artifactPath: `inputs/context/${sourceKind}.json`,
		path,
		quote,
		quoteRedacted: false,
		startLine: 12,
		endLine: quote.includes("\n") ? 13 : 12,
		// The server enforces the biconditional: a side is set exactly when the kind is the diff.
		...(isDiff ? { side: "NEW" as const } : {}),
		...overrides,
	};
}

const meta = {
	title: "Workspace admin/Practice reviews/Observation evidence",
	component: ObservationEvidence,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: {
		evidence: {
			citations: [
				citation("scm.pull-request.diff"),
				citation("scm.pull-request.diff", {
					path: "webapp/src/components/admin/practice-reviews/ObservationResults.tsx",
					side: "OLD",
					quote: '<TableHead scope="col">Observation</TableHead>',
					startLine: 82,
					endLine: 82,
				}),
				citation("scm.review-threads"),
			],
		},
	},
} satisfies Meta<typeof ObservationEvidence>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("3 passages from 2 sources.");
		canvas.getByRole("heading", { name: "The code changes", level: 4 });
		canvas.getByRole("heading", { name: "Review threads on the code", level: 4 });
		// A code citation is the only one that gets a file coordinate, and the only one with a side.
		canvas.getByText("webapp/src/components/admin/practice-reviews/ReviewRow.tsx:12–13");
		canvas.getByText("before");
		await expect(canvas.queryByText("scm.pull-request.diff")).not.toBeInTheDocument();
	},
};

/**
 * The citations are built by walking the registry, so a kind added to the catalog is rendered here
 * the day it lands. The expected headings are written out rather than read back from the registry,
 * which falls back to the raw contract id for a kind it has no words for — asking it what the
 * heading should say would make a missing label agree with itself.
 */
export const EverySource: Story = {
	args: {
		evidence: { citations: knownEvidenceSourceKinds().map((kind) => citation(kind)) },
	},
	play: async ({ canvas }) => {
		// Compared whole rather than one `getByRole` per label, so a kind that arrives without words
		// fails as an id this table does not list rather than going unasserted.
		await expect(
			canvas
				.getAllByRole("heading", { level: 4 })
				.map((heading) => heading.textContent)
				.sort(),
		).toEqual(
			[
				"The pull request itself",
				"The code changes",
				"Comments on the pull request",
				"Files in the repository",
				"The issue itself",
				"Comments on the issue",
				"The document itself",
				"The conversation",
				"Linked issues and requests",
				"Review threads on the code",
				"Review comments",
				"What this project contains",
				"Referenced documents",
				"Earlier observations",
				"Feedback already sent",
			].sort(),
		);
		// Only a `code` locator has trustworthy line numbers, so no other source prints one.
		await expect(canvas.queryByText(/Message from Ada Lovelace:\d/)).not.toBeInTheDocument();
	},
};

/**
 * Outside a `code` locator the line range is an offset into the serialised context file the quote
 * was pulled from — a line of a JSON blob, not a message of a Slack thread — and the server never
 * checks that it points at the quote. Both citations here claim the same lines; only one of them is
 * a location a reader could open.
 */
export const LineNumbersOnlyWhereTheyAreReal: Story = {
	args: {
		evidence: {
			citations: [citation("scm.repository.tree"), citation("slack.conversation.thread")],
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("webapp/src/lib/artifact-kinds.ts:12–13");
		canvas.getByText("Message from Ada Lovelace");
		await expect(canvas.queryByText(/Message from Ada Lovelace:12/)).not.toBeInTheDocument();
	},
};

/**
 * The server accepts a citation with no quote from exactly one detector, so when that detector ran
 * the reason for the blank is knowable and can be said instead of shown as an empty panel. The
 * location is still shown, because that is the part an operator can act on.
 */
export const RedactedQuote: Story = {
	args: {
		detector: "secret-diff-scanner",
		evidence: {
			citations: [citation("scm.pull-request.diff", { quote: undefined, quoteRedacted: true })],
		},
	},
	play: async ({ canvas, canvasElement }) => {
		canvas.getByText(/This looked like a credential/);
		canvas.getByText("webapp/src/components/admin/practice-reviews/ReviewRow.tsx:12–13");
		await expect(canvasElement.querySelector("pre")).toBeNull();
	},
};

export const UnknownSource: Story = {
	args: {
		evidence: {
			citations: [
				{
					sourceKind: "wiki.page.body",
					artifactPath: "inputs/context/wiki.json",
					path: "Onboarding",
					quote: "New reviewers shadow an existing reviewer for their first week.",
					quoteRedacted: false,
					startLine: 4,
					endLine: 4,
				},
			],
		},
	},
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "wiki.page.body", level: 4 });
		canvas.getByText("A source this version of the app has no description for.");
	},
};

export const NoEvidence: Story = {
	args: { evidence: null },
	play: async ({ canvas }) => {
		canvas.getByText(/Nothing was quoted for this observation/);
	},
};

/** A long path and a wide code quote must not push the page sideways on a phone. */
export const Mobile: Story = {
	args: {
		evidence: {
			citations: [
				citation("scm.pull-request.diff", {
					path: "webapp/src/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/targets/$artifactKind/$artifactId.tsx",
					quote:
						"const detectionOutputRouteNameThatIsMuchLongerThanAnyMobileViewport = buildRouteName();",
				}),
			],
		},
	},
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: "The code changes", level: 4 });
		await expectNoPageOverflow();
	},
};
