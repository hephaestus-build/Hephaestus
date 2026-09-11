import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";
import type { PracticeGroupReviewRun } from "@/api/types.gen";
import { daysBefore } from "@/components/common/story-clock";
import { expectNoPageOverflow } from "@/test/reflow";
import { ReviewRunCard } from "./ReviewRunCard";

const run: PracticeGroupReviewRun = {
	reviewId: "00000000-0000-0000-0000-000000000101",
	reviewedAt: daysBefore(2),
	reviewedWork: {
		type: "scm.pull_request",
		id: 902,
		provider: "GITHUB",
		number: 902,
		title: "Split the practice catalog loader per workspace",
		repositoryName: "HephaestusTest/practice-validation",
		url: "https://github.com/HephaestusTest/practice-validation/pull/902",
	},
	observations: [
		{
			observationId: "00000000-0000-0000-0000-000000000102",
			practiceSlug: "records-decisions",
			practiceName: "Record significant decisions",
			title: "The workspace trade-off is documented",
			assessmentStatus: "ASSESSED",
			presence: "PRESENT",
			assessment: "GOOD",
		},
		{
			observationId: "00000000-0000-0000-0000-000000000103",
			practiceSlug: "keeps-docs-current",
			practiceName: "Keep linked documentation current",
			title: "A linked page still uses the old component name",
			assessmentStatus: "ASSESSED",
			presence: "ABSENT",
			assessment: "BAD",
			severity: "MINOR",
		},
	],
};

const meta = {
	title: "Profile/Review runs/Review run card",
	component: ReviewRunCard,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
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

export const Default: Story = { args: { run } };
export const SlackConversation: Story = {
	args: {
		run: {
			...run,
			reviewId: "00000000-0000-0000-0000-000000000201",
			reviewedWork: {
				type: "chat.conversation_thread",
				id: 41,
				provider: "SLACK",
				channelName: "backend-guild",
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
				type: "docs.document",
				id: 77,
				provider: "OUTLINE",
				title: "Runbook: rotating the signing key",
				url: "https://outline.example.com/doc/runbook-rotating-the-signing-key",
			},
		},
	},
};
export const GitLabMergeRequest: Story = {
	args: {
		run: {
			...run,
			reviewId: "00000000-0000-0000-0000-000000000203",
			reviewedWork: {
				...run.reviewedWork,
				provider: "GITLAB",
				number: 128,
				title: "Move the export retention sweep into a transaction",
				repositoryName: "aet/hephaestus",
				url: "https://gitlab.example.com/aet/hephaestus/-/merge_requests/128",
			},
		},
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

const denseRun: PracticeGroupReviewRun = {
	...run,
	reviewId: "00000000-0000-0000-0000-000000000205",
	observations: [
		...run.observations,
		{
			observationId: "00000000-0000-0000-0000-000000000104",
			practiceSlug: "small-changes",
			practiceName: "Keep changes focused",
			title: "The refactor and the fix arrived together",
			assessmentStatus: "ASSESSED",
			presence: "PRESENT",
			assessment: "BAD",
			severity: "MAJOR",
		},
		{
			observationId: "00000000-0000-0000-0000-000000000105",
			practiceSlug: "covers-new-behavior",
			practiceName: "Cover new behavior with a test",
			title: "The new branch has no test exercising it",
			assessmentStatus: "ASSESSED",
			presence: "ABSENT",
			assessment: "BAD",
			severity: "CRITICAL",
		},
	],
};
export const ManyObservations: Story = {
	args: { run: denseRun, onToggleObservation: fn() },
	play: async ({ canvas, userEvent }) => {
		// The fourth observation is the one held back, and the button counts it rather than saying "more".
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
				title: "Split the practice catalog loader per workspace and move the seeding behind a flag",
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
