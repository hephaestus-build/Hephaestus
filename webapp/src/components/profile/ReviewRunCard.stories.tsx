import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import type { ObservationDetail, PracticeGroupReviewRun } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";

import { ReviewRunCard } from "./ReviewRunCard";

/** What every observation of the run below shares: the work, the moment, the ordinary origin. */
const onTheRun = {
	assessmentStatus: "ASSESSED",
	observedAt: daysBefore(2),
	origin: "LIVE",
	claimCurrentness: "CURRENT",
	artifactId: 902,
	artifactKind: "scm.pull_request",
	artifactUrl: "https://github.com/HephaestusTest/practice-validation/pull/902",
} satisfies Partial<ObservationDetail>;

const run: PracticeGroupReviewRun = {
	reviewId: "00000000-0000-0000-0000-000000000101",
	reviewedAt: daysBefore(2),
	reviewedWork: {
		kind: "scm.pull_request",
		provider: "GITHUB",
		id: "902",
		label: "#902",
		repositoryName: "HephaestusTest/practice-validation",
		url: "https://github.com/HephaestusTest/practice-validation/pull/902",
	},
	observations: [
		{
			...onTheRun,
			id: "00000000-0000-0000-0000-000000000102",
			practiceSlug: "records-decisions",
			practiceName: "Record significant decisions",
			summary: "The workspace trade-off is documented",
			presence: "PRESENT",
			assessment: "GOOD",
			evidenceRationale:
				"The description records why one workspace per team was chosen over one per repository.",
		},
		{
			...onTheRun,
			id: "00000000-0000-0000-0000-000000000103",
			feedbackResponse: { feedbackId: "00000000-0000-0000-0000-000000000104" },
			practiceSlug: "keeps-docs-current",
			practiceName: "Keep linked documentation current",
			summary: "A linked page still uses the old component name",
			presence: "ABSENT",
			assessment: "GOOD",
			severity: "MINOR",
			evidenceRationale:
				"The page the description links still calls the component by the name this change retires.",
			deliveredFeedback: "Rename it on the linked page in the same change.",
		},
	],
};

const meta = {
	component: ReviewRunCard,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { run, observations: { onRespond: fn() } },
	decorators: [
		(Story) => (
			<ol>
				<Story />
			</ol>
		),
	],
} satisfies Meta<typeof ReviewRunCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The work at the head, linked once; under it every observation open, and none links the work
 * again.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("link")).toHaveLength(1);
		for (const { summary } of run.observations) {
			await expect(canvas.getByRole("button", { name: new RegExp(summary, "u") })).toHaveAttribute(
				"aria-expanded",
				"true",
			);
		}
		await expect(canvas.getByText("Next step")).toBeVisible();
	},
};
export const SlackConversation: Story = {
	args: {
		run: {
			...run,
			reviewId: "00000000-0000-0000-0000-000000000201",
			reviewedWork: {
				kind: "chat.conversation_thread",
				id: "C01/p1700000000",
				label: "#backend-guild",
				url: "https://example.slack.com/archives/C01/p1700000000",
			},
		},
	},
};
export const OutlineDocument: Story = {
	args: {
		run: {
			...run,
			reviewId: "00000000-0000-0000-0000-000000000202",
			reviewedWork: {
				kind: "docs.document",
				id: "77",
				label: "Runbook: rotating the signing key",
				url: "https://outline.example.com/doc/runbook-rotating-the-signing-key",
			},
		},
	},
};
/**
 * The mark is the forge the work lives at, not its kind: beside `!128` a pull-request glyph would
 * say the same thing twice and leave a GitLab request looking like a GitHub one.
 */
export const GitLabMergeRequest: Story = {
	args: {
		run: {
			...run,
			reviewId: "00000000-0000-0000-0000-000000000203",
			reviewedWork: {
				...run.reviewedWork,
				provider: "GITLAB",
				id: "128",
				label: "!128",
				repositoryName: "aet/hephaestus",
				url: "https://gitlab.example.com/aet/hephaestus/-/merge_requests/128",
			},
		},
	},
	play: async ({ canvas }) => {
		// The glyph is decorative, so the mark is read off the brand icon's own <title>.
		canvas.getByTitle("GitlabIcon");
		await expect(canvas.queryByTitle("GithubIcon")).toBeNull();
	},
};
export const WithoutALink: Story = {
	args: {
		run: {
			...run,
			reviewId: "00000000-0000-0000-0000-000000000204",
			reviewedWork: { ...run.reviewedWork, url: undefined, repositoryName: undefined },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

/** A run that reached one practice once, which is every card on a practice's own level. */
const soleRun: PracticeGroupReviewRun = {
	...run,
	reviewId: "00000000-0000-0000-0000-000000000206",
	observations: [
		{
			...onTheRun,
			id: "00000000-0000-0000-0000-000000000107",
			feedbackResponse: { feedbackId: "00000000-0000-0000-0000-000000000108" },
			practiceSlug: "small-changes",
			practiceName: "Keep changes focused",
			summary: "The refactor and the fix arrived together",
			presence: "PRESENT",
			assessment: "BAD",
			severity: "MAJOR",
			evidenceRationale:
				"The package move and the caching change land in one diff, so neither can be reverted alone.",
			deliveredFeedback: "Land the rename on its own first.",
		},
	],
};

/**
 * One observation is one thing: the summary leads, the work it was seen on is the small line
 * under it, and no head is divided off above them.
 */
export const OneObservation: Story = {
	args: { run: soleRun },
	play: async ({ canvas }) => {
		const [, observationRow] = canvas.getAllByRole("listitem");
		if (!observationRow) {
			throw new Error("Expected the card's one observation row.");
		}
		// The row is the first thing in the card, so no head is divided off above it.
		const rows = observationRow.closest("ul");
		await expect(rows?.parentElement?.firstElementChild).toBe(rows);

		// The work the head used to name is inside the observation's own row, still linked.
		const summary = canvas.getByText("The refactor and the fix arrived together");
		const work = canvas.getByRole("link", { name: /^#902/u });
		await expect(observationRow).toContainElement(summary);
		await expect(observationRow).toContainElement(work);
		await expect(canvas.getByText("HephaestusTest/practice-validation")).toBeVisible();
		// The summary is the anchor, so it comes first and the work reads as the note beneath it.
		await expect(summary.compareDocumentPosition(work)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
		await expect(canvas.getByText("Next step")).toBeVisible();
	},
};

const denseRun: PracticeGroupReviewRun = {
	...run,
	reviewId: "00000000-0000-0000-0000-000000000205",
	observations: [
		...run.observations,
		{
			...onTheRun,
			id: "00000000-0000-0000-0000-000000000105",
			practiceSlug: "small-changes",
			practiceName: "Keep changes focused",
			summary: "The refactor and the fix arrived together",
			presence: "PRESENT",
			assessment: "BAD",
			severity: "MAJOR",
		},
		{
			...onTheRun,
			id: "00000000-0000-0000-0000-000000000106",
			practiceSlug: "covers-new-behavior",
			practiceName: "Cover new behavior with a test",
			summary: "The new branch has no test exercising it",
			presence: "ABSENT",
			assessment: "GOOD",
			severity: "CRITICAL",
		},
	],
};
export const ManyObservations: Story = {
	args: { run: denseRun },
	play: async ({ canvas, userEvent }) => {
		// The fourth observation is the one held back, and the button counts it rather than saying
		// "more".
		const held = "The new branch has no test exercising it";
		await expect(canvas.queryByText(held)).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "Show more (1)" }));
		await expect(canvas.getByText(held)).toBeVisible();

		await userEvent.click(canvas.getByRole("button", { name: "Show less" }));
		await expect(canvas.queryByText(held)).toBeNull();
	},
};
export const MobileReflow: Story = {
	args: {
		run: {
			...run,
			reviewedWork: {
				...run.reviewedWork,
				kind: "docs.document",
				label: "Split the practice catalog loader per workspace and move the seeding behind a flag",
				repositoryName: "ls1intum/hephaestus-practice-validation-fixtures",
			},
		},
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
