import type { Meta, StoryObj } from "@storybook/react-vite";
import type { ComponentProps } from "react";
import { expect, within } from "storybook/test";

import type { ItemRef, ProfileReview, ResultList } from "@/api/types.gen";
import { useStoryFeedbackResponses } from "@/stories/feedback-ratings";
import {
	detailFeedback,
	detailResult,
	listedResult,
	metWithExample,
	ownFeedback,
	reviewRef,
} from "@/stories/practice-detail-story-mock-data";
import { pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";

import { ReviewCard } from "./ReviewCard";

function Answerable(props: ComponentProps<typeof ReviewCard>) {
	const responses = useStoryFeedbackResponses();
	return <ReviewCard {...props} responses={responses} />;
}

const pullRequest902: ItemRef = pullRequest(902);
const REVIEW = "00000000-0000-0000-0000-000000000101";

function met(id: string, practiceSlug: string, practiceName: string, summary: string): ResultList {
	return {
		...listedResult(metWithExample),
		id,
		reviewId: REVIEW,
		practiceSlug,
		practiceName,
		summary,
	};
}

const review: ProfileReview = {
	review: reviewRef(REVIEW, pullRequest902, daysBefore(2)),
	positionOnWork: 1,
	totalOnWork: 1,
	results: [
		met(
			"00000000-0000-0000-0000-000000000102",
			"records-decisions",
			"Record significant decisions",
			"The workspace trade-off is documented",
		),
		{
			...listedResult(detailResult),
			id: "00000000-0000-0000-0000-000000000103",
			reviewId: REVIEW,
			practiceSlug: "keeps-docs-current",
			practiceName: "Keep linked documentation current",
			summary: "A linked page still uses the old component name",
		},
	],
	feedback: [detailFeedback],
};

const meta = {
	component: ReviewCard,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { review },
	render: (args) => <Answerable {...args} />,
	decorators: [
		(Story) => (
			<ol>
				<Story />
			</ol>
		),
	],
} satisfies Meta<typeof ReviewCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /^#902/u })).toHaveAttribute(
			"href",
			pullRequest902.url,
		);
		const rows = canvas.getAllByRole("button", { expanded: true });
		await expect(rows[0]).toHaveTextContent("A linked page still uses the old component name");
		await expect(canvas.getByText("The workspace trade-off is documented")).toBeVisible();
		await expect(rows).toHaveLength(1);
		const feedback = canvas.getByRole("list", { name: "Your feedback" });
		await expect(within(feedback).getAllByRole("link")).toHaveLength(2);
		await expect(
			within(feedback).getByRole("group", { name: "Your response to the feedback on the work" }),
		).toBeVisible();
	},
};

export const ReadOnly: Story = {
	render: (args) => <ReviewCard {...args} />,
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Your feedback" })).toBeVisible();
		await expect(canvas.queryByRole("group")).toBeNull();
	},
};

export const WithoutFeedback: Story = {
	args: { review: { ...review, feedback: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("list", { name: "Your feedback" })).toBeNull();
	},
};

export const SlackConversation: Story = {
	args: {
		review: {
			...review,
			review: reviewRef(
				"00000000-0000-0000-0000-000000000201",
				{
					...pullRequest902,
					id: "3001",
					kind: "chat.thread",
					provider: "SLACK",
					label: "#backend-guild",
					container: null,
					url: "https://example.slack.com/archives/C01/p1700000000",
				},
				daysBefore(2),
			),
		},
	},
};

export const OutlineDocument: Story = {
	args: {
		review: {
			...review,
			review: reviewRef(
				"00000000-0000-0000-0000-000000000202",
				{
					...pullRequest902,
					id: "4077",
					kind: "docs.document",
					provider: "OUTLINE",
					label: "Runbook: rotating the signing key",
					container: null,
					url: "https://outline.example.com/doc/runbook-rotating-the-signing-key",
				},
				daysBefore(2),
			),
		},
	},
};

export const GitLabMergeRequest: Story = {
	args: {
		review: {
			...review,
			review: reviewRef(
				"00000000-0000-0000-0000-000000000203",
				{
					...pullRequest902,
					id: "1128",
					provider: "GITLAB",
					label: "!128",
					container: "aet/hephaestus",
					url: "https://gitlab.example.com/aet/hephaestus/-/merge_requests/128",
				},
				daysBefore(2),
			),
		},
	},
};

export const WithoutALink: Story = {
	args: {
		review: {
			...review,
			review: reviewRef(
				"00000000-0000-0000-0000-000000000204",
				{ ...pullRequest902, url: null, container: null },
				daysBefore(2),
			),
			feedback: [ownFeedback(detailFeedback.id)],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

const soleReview: ProfileReview = {
	...review,
	review: reviewRef("00000000-0000-0000-0000-000000000206", pullRequest902, daysBefore(2)),
	results: [{ ...listedResult(detailResult), practiceName: "Keep changes focused" }],
};

export const OneResult: Story = {
	args: { review: soleReview },
	play: async ({ canvas }) => {
		const [, resultRow] = canvas.getAllByRole("listitem");
		if (!resultRow) {
			throw new Error("Expected the card's one result row.");
		}
		const rows = resultRow.closest("ul");
		await expect(rows?.parentElement?.firstElementChild).toBe(rows);

		const summary = canvas.getByText("The refactor and the fix arrived together");
		const item = canvas.getByRole("link", { name: /^#902/u });
		await expect(resultRow).toContainElement(summary);
		await expect(resultRow).toContainElement(item);
		await expect(canvas.getByText("HephaestusTest/practice-validation")).toBeVisible();
		await expect(summary.compareDocumentPosition(item)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
	},
};

const denseReview: ProfileReview = {
	...review,
	review: reviewRef("00000000-0000-0000-0000-000000000205", pullRequest902, daysBefore(2)),
	results: [
		met(
			"00000000-0000-0000-0000-000000000111",
			"records-decisions",
			"Record significant decisions",
			"The workspace trade-off is documented",
		),
		met(
			"00000000-0000-0000-0000-000000000105",
			"small-changes",
			"Keep changes focused",
			"The change does one thing",
		),
		met(
			"00000000-0000-0000-0000-000000000109",
			"describe-what-and-why",
			"Describe what changed and why",
			"The description states the goal",
		),
		{
			...listedResult(detailResult),
			id: "00000000-0000-0000-0000-000000000106",
			reviewId: REVIEW,
			practiceSlug: "covers-new-behavior",
			practiceName: "Cover new behavior with a test",
			summary: "The new branch has no test exercising it",
		},
	],
};

export const ManyResults: Story = {
	args: { review: denseReview, openOnArrival: () => false },
	play: async ({ canvas, userEvent }) => {
		await expect(
			canvas.getByRole("button", { name: /The new branch has no test exercising it/u }),
		).toBeVisible();

		const held = "The description states the goal";
		await expect(canvas.queryByText(held)).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Show 1 more observation" }));
		await expect(canvas.getByText(held)).toBeVisible();
		for (const result of denseReview.results) {
			await expect(canvas.getByText(result.summary ?? result.practiceName)).toBeVisible();
		}

		await userEvent.click(canvas.getByRole("button", { name: "Show less" }));
		await expect(canvas.queryByText(held)).toBeNull();
		await expect(canvas.getByText("The new branch has no test exercising it")).toBeVisible();
	},
};

export const MobileReflow: Story = {
	args: {
		review: {
			...review,
			review: reviewRef(
				"00000000-0000-0000-0000-000000000207",
				{
					...pullRequest902,
					kind: "docs.document",
					label:
						"Split the practice catalog loader per workspace and move the seeding behind a flag",
					container: "ls1intum/hephaestus-practice-validation-fixtures",
				},
				daysBefore(2),
			),
		},
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
