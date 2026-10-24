import type { Meta, StoryObj } from "@storybook/react-vite";
import type { ComponentProps } from "react";
import { expect, screen, within } from "storybook/test";

import type { ResultDetail, ResultList } from "@/api/types.gen";
import { formatDay } from "@/lib/dates";
import { useStoryFeedbackResponses } from "@/stories/feedback-ratings";
import { expectSettledVisible } from "@/stories/overlay";
import {
	detailFeedback,
	detailResult,
	listedResult,
	metWithExample,
	metWithoutSummary,
	searchedAndFoundNothing,
	undeterminedResult,
} from "@/stories/practice-detail-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";

import { ReviewResultRow } from "./ReviewResultRow";

function Answerable(props: ComponentProps<typeof ReviewResultRow>) {
	const responses = useStoryFeedbackResponses();
	return <ReviewResultRow {...props} responses={responses} />;
}

const strength: ResultDetail = {
	...metWithExample,
	id: "00000000-0000-0000-0000-000000000101",
	practiceSlug: "explains-decisions",
	practiceName: "Explain significant decisions",
	summary: "The reasoning is recorded next to the changed behavior",
	example: null,
	explanation:
		"The comment above the changed branch states why the timeout was raised, so a later reader does not have to reconstruct it from the diff.",
	evidence: {
		search: null,
		inapplicability: null,
		openQuestions: [],
		citations: [
			{
				sourceKind: "scm.pull-request.diff",
				sourcePath: "owner/repo#4821",
				path: "server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/AgentClient.java",
				side: "NEW",
				commit: null,
				startLine: 88,
				endLine: 90,
				quote:
					"// Raised from 30s: the practice script step regularly needs 45s on a cold cache.\n" +
					"private static final Duration TIMEOUT = Duration.ofSeconds(90);",
				verification: null,
			},
			{
				sourceKind: "scm.pull-request.diff",
				sourcePath: "owner/repo#4821",
				path: "server/application/src/main/resources/application.yml",
				side: "NEW",
				commit: null,
				startLine: 12,
				endLine: 12,
				quote: "agent.timeout: 90s",
				verification: null,
			},
		],
	},
	feedback: [detailFeedback],
};

const outcomes: ResultList[] = [
	{ ...listedResult(detailResult), problems: [], summary: "The exception is caught and discarded" },
	{
		...listedResult(metWithExample),
		summary: "The boundary does not fall back to an unsafe value",
	},
	{
		...listedResult(metWithExample),
		id: "00000000-0000-0000-0000-000000000401",
		outcome: "DOES_NOT_APPLY",
		summary: "This change performs no network request",
	},
	{
		...listedResult(undeterminedResult),
		summary: "The evidence does not settle whether the page is current",
	},
];

const meta = {
	component: ReviewResultRow,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { result: strength },
	render: (args) => <Answerable {...args} />,
	decorators: [
		(Story) => (
			<ul className="divide-y rounded-lg border">
				<Story />
			</ul>
		),
	],
} satisfies Meta<typeof ReviewResultRow>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const row = canvas.getByRole("button", { name: /The reasoning is recorded/u });
		await expect(row).toHaveAttribute("aria-expanded", "true");
		await expect(canvas.getByText("Explain significant decisions")).toBeVisible();
		await expect(canvas.getByText("Met")).toBeVisible();
		await expect(canvas.queryByText(formatDay(strength.recordedAt))).toBeNull();
		const labels = ["Why it was noted", "Evidence", "Feedback", "Your response"];
		const positions = labels.map((label) => canvas.getByText(label).getBoundingClientRect().top);
		await expect([...positions].sort((a, b) => a - b)).toStrictEqual(positions);
		await expect(
			canvas.getByText("private static final Duration TIMEOUT = Duration.ofSeconds(90);"),
		).toBeVisible();
		await expect(canvas.getByText("agent.timeout: 90s")).toBeVisible();
		await expect(
			canvas.getByRole("group", { name: "Your response to the feedback on the work" }),
		).toBeVisible();
	},
};

export const ListedResult: Story = {
	args: { result: listedResult(detailResult) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: /The refactor and the fix arrived together/u }),
		).toHaveTextContent("Not met · 3 problems");
		const problems = canvas.getByRole("list", { name: "Problems" });
		await expect(within(problems).getAllByRole("listitem")).toHaveLength(3);
		await expect(canvas.queryByText("Why it was noted")).toBeNull();
		await expect(canvas.queryByText("Feedback")).toBeNull();
		await expect(canvas.queryByRole("group")).toBeNull();
	},
};

export const StaleWithoutEvidence: Story = {
	args: {
		result: { ...strength, currentness: "STALE", explanation: null, evidence: null },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("The practice or the reviewed work changed after this observation."),
		).toBeVisible();
		await expect(canvas.queryByText("Why it was noted")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Evidence")).not.toBeInTheDocument();
		await expect(canvas.getByText("Feedback")).toBeVisible();
	},
};

export const MarkedIncorrect: Story = {
	args: { result: { ...strength, markedIncorrectAt: daysBefore(1) } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Marked incorrect")).toBeVisible();
		await expect(
			canvas.getByText("A workspace admin marked this observation as incorrect."),
		).toBeVisible();
	},
};

export const MetWithoutSummary: Story = {
	args: { result: metWithoutSummary },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText(metWithoutSummary.practiceName)).toHaveLength(1);
		await expect(canvas.getByText(metWithoutSummary.practiceName)).toBeVisible();
	},
};

export const EveryOutcome: Story = {
	render: (args) => (
		<>
			{outcomes.map((result) => (
				<ReviewResultRow key={result.id} {...args} result={result} />
			))}
		</>
	),
	play: async ({ canvas }) => {
		const buttons = canvas.getAllByRole("button");
		await expect(buttons.map((button) => button.textContent)).toStrictEqual([
			"Not met",
			"Met",
			"Does not apply",
			"Could not determine",
		]);
		for (const button of buttons) {
			await expect(button).not.toHaveAttribute("aria-expanded");
		}
		await expect(canvas.queryByText("Major")).toBeNull();
		await expect(canvas.queryByText("Critical")).toBeNull();
	},
};

export const OutcomeExplained: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.hover(canvas.getByText("Met"));
		await expectSettledVisible(
			await screen.findByText(/practice standard is met in the reviewed evidence/u),
		);
	},
};

export const MobileReflow: Story = {
	args: {
		result: { ...strength, outcome: "NOT_MET", severity: "MAJOR", origin: "BACKFILL" },
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Backfilled")).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const SearchedAndFoundNothing: Story = {
	args: { result: searchedAndFoundNothing },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("What was checked")).toBeVisible();
		await expect(canvas.getByText("Looked for:")).toBeVisible();
		await expect(
			canvas.getByText("a test exercising the new caching branch of the loader"),
		).toBeVisible();
		await expect(
			canvas.getByText("The code changes and Files and history in the repository"),
		).toBeVisible();
		await expect(canvas.getByText("How far it reached:")).toBeVisible();
		await expect(
			canvas.getByText(/^every test file the diff touches/u, { selector: "p" }),
		).toBeVisible();
		const explanation = canvas.getByText(/^The branch is new in this change/u, { selector: "p" });
		await expect(within(explanation).getByText("loadFromCache").tagName).toBe("CODE");
		await expect(canvas.queryByText(/`/u)).toBeNull();
	},
};

export const NotMetWithProblems: Story = {
	args: { result: detailResult },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: /The refactor and the fix arrived together/u }),
		).toHaveTextContent("Not met · 3 problems");
		const problems = canvas.getByRole("list", { name: "Problems" });
		const items = within(problems).getAllByRole("listitem");
		await expect(items).toHaveLength(3);
		await expect(
			within(items[0] ?? problems).getByText("The caching change hides inside the package move"),
		).toBeVisible();
		await expect(within(problems).getByText("PracticeCatalogLoader").tagName).toBe("CODE");
		await expect(
			within(problems).getByText("Move the class in one commit and add the cache in the next."),
		).toBeVisible();
		for (const label of ["Critical", "Major", "Minor", "Trivial"]) {
			await expect(canvas.queryByText(label)).toBeNull();
		}
		await expect(
			within(problems).getByText(
				'@Cacheable(cacheNames = "practice-catalog", key = "#workspaceId")',
			),
		).toBeVisible();
	},
};

export const CouldNotDetermine: Story = {
	args: { result: undeterminedResult },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Could not determine")).toBeVisible();
		const questions = canvas.getByRole("list", { name: "Open questions" });
		await expect(within(questions).getAllByRole("listitem")).toHaveLength(2);
		await expect(
			within(questions).getByText("Was the package move asked for in the linked issue?"),
		).toBeVisible();
		await expect(within(questions).getAllByText("Would settle it:")).toHaveLength(2);
		await expect(
			within(questions).getByText("The text of issue #880, which the description links."),
		).toBeVisible();
		await expect(within(questions).getByText('"body" : "Closes #880"')).toBeVisible();
		await expect(canvas.queryByText("Problems")).toBeNull();
		await expect(canvas.queryByText("What was checked")).toBeNull();
	},
};

export const ExampleFromThisWork: Story = {
	args: { result: metWithExample },
	play: async ({ canvas }) => {
		const example = canvas.getByText("Example from this work").parentElement;
		if (example === null) {
			throw new Error("The example has no section around its label.");
		}
		await expect(
			within(example).getByText(
				"This commit moves the loader and changes nothing else, so it can be reverted on its own.",
			),
		).toBeVisible();
		await expect(
			within(example).getByText("package de.tum.cit.aet.hephaestus.practices.catalog;"),
		).toBeVisible();
		await expect(example.querySelector("[class*='success']")).toBeNull();
		await expect(canvas.queryByText(/great|well done|nice work/iu)).toBeNull();
	},
};

export const HiddenHistoryText: Story = {
	args: {
		result: {
			...metWithoutSummary,
			summary: null,
			explanation: null,
			evidence: null,
			problems: [],
			currentness: "UNVERIFIABLE",
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Observation text unavailable");
		canvas.getByText(metWithoutSummary.practiceName);
	},
};
