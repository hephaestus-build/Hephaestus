import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";
import { RESULT_WORDS_UNAVAILABLE } from "./result-words";

import type { AccountRef, ResultMark, ReviewResultDetail } from "@/api/types.gen";
import {
	DELIVERY_STATE_DEFS,
	deliveryOutcome,
} from "@/components/practice-vocabulary/delivery-state-defs";
import { FEEDBACK_DECISION_DEFS } from "@/components/practice-vocabulary/feedback-decision-defs";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import {
	expectSectionHeadings,
	expectSectionLines,
	expectSectionsFold,
} from "@/stories/section-lines";
import { daysBefore, hoursBefore } from "@/stories/story-clock";
import { expectGenuinelyDisabled } from "@/test/controls";
import { levelsOpenedBy } from "@/test/detail-stack";
import { precedes } from "@/test/dom";

import { resultDetail, reviewResultDetail } from "./fixtures";
import { forgetLevelFolds } from "./level-folds";
import { SHEET_SECTIONS } from "./RecordSheet";
import { ResultLevel } from "./ResultLevel";
import { resultLevel } from "./review-levels";

const ready = (result: ReviewResultDetail) => ({ status: "ready" as const, result });

const ADA_ADMIN: AccountRef = {
	id: "3",
	displayName: "Ada Admin",
	avatarUrl: null,
	status: "ACTIVE",
	identities: [],
};

function mark(
	overrides: Partial<ResultMark> & Pick<ResultMark, "id" | "kind" | "reason">,
): ResultMark {
	return {
		resultId: reviewResultDetail.id,
		createdAt: hoursBefore(2),
		createdBy: ADA_ADMIN,
		removedAt: null,
		removedBy: null,
		removalReason: null,
		...overrides,
	};
}

const markedIncorrect: ReviewResultDetail = {
	...reviewResultDetail,
	disagreements: [],
	markedIncorrectAt: hoursBefore(2),
	marks: [
		mark({
			id: "mark-2",
			kind: "INCORRECT",
			reason: "The 404 comes from the router, not the cache.",
		}),
		mark({
			id: "mark-1",
			kind: "INCORRECT",
			reason: "Looked like a false positive at first.",
			createdAt: daysBefore(3),
			removedAt: daysBefore(2),
			removalReason: "The diff does conflate the two after all.",
		}),
	],
};

const markedAccepted: ReviewResultDetail = {
	...reviewResultDetail,
	disagreements: [],
	acceptedAt: daysBefore(1),
	marks: [
		mark({
			id: "mark-3",
			kind: "ACCEPTED",
			reason: "The two cases do need different answers.",
			createdAt: daysBefore(1),
		}),
	],
};

const meta = {
	component: ResultLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		result: ready(reviewResultDetail),
		onMark: fn(async () => undefined),
		onRemoveMark: fn(async () => undefined),
		isMarking: false,
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={resultLevel(reviewResultDetail.id)} path={args.path}>
			{(level) => <ResultLevel {...args} {...level} />}
		</InLevelStack>
	),
	// A fold is kept in this browser, so every story starts with all sections open.
	beforeEach: () => {
		forgetLevelFolds("result");
		return () => forgetLevelFolds("result");
	},
} satisfies Meta<typeof ResultLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ userEvent }) => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(
			panel.getByRole("heading", {
				name: "A cache miss and a permission failure come back as the same 404",
				level: 2,
			}),
		).toBeVisible();
		await expect(
			panel.queryByRole("group", { name: "How this came about" }),
		).not.toBeInTheDocument();
		const links = panel.queryAllByRole("link");
		// A sheet links its own relatives: the result's problems and feedback. A reference with nowhere to go
		// has no address.
		for (const link of links) {
			await expect(String(link.getAttribute("href"))).toMatch(
				/^(?:https:\/\/|null$)|\?detail=(?:problem|feedback):[^&]+$/u,
			);
		}
		await expect(
			panel.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent),
		).toStrictEqual([
			SHEET_SECTIONS.what,
			SHEET_SECTIONS.state,
			SHEET_SECTIONS.details,
			SHEET_SECTIONS.technical,
		]);
		panel.getByRole("heading", { name: "Why this observation", level: 4 });
		// One line between each two sections, one section size, and every section folds.
		await expectSectionLines(panelElement, 3);
		await expectSectionHeadings(panelElement);
		await expectSectionsFold(panelElement, userEvent);
		await expect(panel.getByText("Not met · 3 problems")).toBeVisible();
		await expect(panel.getAllByText("Major")).toHaveLength(2);
		await expect(panel.queryByText(/citations/u)).not.toBeInTheDocument();
		await expect(panel.getByRole("button", { name: "Mark incorrect" })).toBeEnabled();
		await expect(panel.getByRole("button", { name: "Mark accepted" })).toBeEnabled();
		await expect(panel.queryByRole("button", { name: "Remove mark" })).not.toBeInTheDocument();
	},
};

export const ByHand: Story = {
	args: { result: ready({ ...reviewResultDetail, origin: "REQUEST" }) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("Started by");
		await expect(panel.getByText("Requested")).toBeVisible();
		panel.getByText("Someone asked for this review.");
	},
};

export const MarkdownExplanation: Story = {
	args: {
		result: ready({
			...reviewResultDetail,
			explanation:
				"### Access check\n\n`CacheService.find` returns 404 for a cache miss and for a caller who may not read the entry. The second case is a `403`.",
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("heading", { name: "Why this observation", level: 4 });
		await expect(panel.getByRole("heading", { name: "Access check", level: 5 })).toBeVisible();
		const code = panel.getByText("CacheService.find", { selector: "code" });
		await expect(code).toBeVisible();
		// The typography plugin draws a backtick on each side of inline code unless told not to.
		await expect(getComputedStyle(code, "::before").content).toBe("none");
	},
};

export const Problems: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const rows = [...panel.getByRole("list", { name: "Problems" }).children].filter(
			(row): row is HTMLElement => row instanceof HTMLElement,
		);
		await expect(rows.map((row) => row.querySelector("p")?.textContent)).toEqual([
			"A missing row and a missing grant both become a 404",
			"The guard’s 403 never reaches this endpoint",
			"The NotFoundException carries no message",
		]);
		const [first, second, third] = rows.map((row) => within(row));
		if (first === undefined || second === undefined || third === undefined) {
			throw new Error("The sheet does not list three problems");
		}
		await expect(first.getByText("Major")).toBeVisible();
		await expect(second.getByText("Minor")).toBeVisible();
		await expect(third.getByText("Trivial")).toBeVisible();
		await expect(first.getByText("findVisible", { selector: "code" })).toBeVisible();
		first.getByText(/ReviewQueryService\.java:116–119$/u);
		await expect(
			first.getByText(
				"Fetch the row first, then check the grant, and answer 403 when the caller may not read it.",
			),
		).toBeVisible();
		await expect(second.queryByText("Next step")).not.toBeInTheDocument();
		const evidence = within(panel.getByRole("region", { name: "Evidence" }));
		evidence.getByText("Do we lose the difference between 'gone' and 'not yours' here?");
		await expect(panel.queryByText("Example from this work")).not.toBeInTheDocument();
		await expect(panel.queryByText(/maps every error in one handler/u)).not.toBeInTheDocument();
		await expect(panel.queryByRole("region", { name: "Open questions" })).not.toBeInTheDocument();
	},
};

export const OpenQuestions: Story = {
	args: { result: ready(resultDetail("22222222-5555-5555-5555-555555555555")) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getAllByText("Could not determine")[0]).toBeVisible();
		const questions = within(panel.getByRole("list", { name: "Open questions" }));
		await expect(questions.getAllByRole("listitem")).toHaveLength(2);
		await expect(
			questions.getByText("Was the restore order agreed with the on-call team?"),
		).toBeVisible();
		questions.getByText(
			"Would settle it: A link to the discussion or incident that chose this order.",
		);
		await expect(
			panel.getByRole("link", { name: /^Runbook: restoring a workspace from backup/u }),
		).toBeVisible();
		await expect(
			questions.queryByText("Runbook: restoring a workspace from backup", { selector: "code" }),
		).not.toBeInTheDocument();
		await expect(questions.queryByText(/^At/u)).not.toBeInTheDocument();
		await expect(panel.queryByRole("list", { name: "Problems" })).not.toBeInTheDocument();
		await expect(panel.queryByText(/problems?$/u)).not.toBeInTheDocument();
	},
};

export const OneProblem: Story = {
	args: { result: ready(resultDetail("88888888-8888-8888-8888-888888888888")) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Not met · 1 problem")).toBeVisible();
		await expect(panel.getAllByText("Trivial").length).toBeGreaterThan(0);
		await expect(panel.queryByRole("region", { name: "Evidence" })).not.toBeInTheDocument();
	},
};

export const Met: Story = {
	args: { result: ready(resultDetail("55555555-5555-5555-5555-555555555555")) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getAllByText("Met")[0]).toBeVisible();
		await expect(panel.queryByRole("list", { name: "Problems" })).not.toBeInTheDocument();
		within(panel.getByRole("region", { name: "Evidence" })).getByText(
			"One passage from one source.",
		);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const Disagreed: Story = {
	args: {
		result: ready({
			...reviewResultDetail,
			disagreements: [
				{
					feedbackId: "dddddddd-2222-2222-2222-222222222222",
					destination: "PRACTICE_PAGE",
					comment: "The retry is the caller's job here; this service only reports the failure.",
					respondedAt: hoursBefore(3),
				},
			],
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getAllByText("Disagreed");
		panel.getByText("The developer disagreed with feedback on the practice page");
		panel.getByText("The retry is the caller's job here; this service only reports the failure.");
		await expect(panel.queryByRole("alert")).toBeNull();
		await expect(
			reviewResultDetail.feedback.map((entry) => `/?detail=feedback:${entry.id}`),
		).toContain(
			// The result's practice-page feedback was delivered, then withdrawn: its receipt stays, flagged.
			panel
				.getByRole("link", { name: "On the practice page, Delivered, withdrawn" })
				.getAttribute("href"),
		);
		panel.getByRole("button", { name: "Mark incorrect" });
	},
};

export const NoLongerCurrent: Story = {
	args: {
		result: ready({
			...resultDetail("bbbbbbbb-2222-2222-2222-222222222222"),
			endedAt: hoursBefore(5),
			endReason: "WORK_CHANGED",
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("Historical observation");
		panel.getByText("No longer current");
		panel.getByText("The work changed after this observation.");
		await expect(panel.queryByRole("alert")).toBeNull();
	},
};

export const EvidenceHidden: Story = {
	args: {
		result: ready({
			...reviewResultDetail,
			evidenceHidden: true,
			evidence: null,
			problems: [],
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("The evidence of this observation is hidden.");
		await expect(panel.queryByText(/Nothing was quoted/u)).toBeNull();
	},
};

export const MarkIncorrect: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Mark incorrect" }));
		const popover = await screen.findByRole("dialog", {
			name: "Mark this observation incorrect",
		});
		const reason = within(popover).getByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		const submit = within(popover).getByRole("button", { name: "Mark incorrect" });
		await expectGenuinelyDisabled(submit);
		await userEvent.type(reason, "  The 404 comes from the router, not the cache.  ");
		await userEvent.click(submit);
		await expect(args.onMark).toHaveBeenCalledWith(
			"INCORRECT",
			"The 404 comes from the router, not the cache.",
		);
		await waitFor(async () => expect(screen.queryByRole("textbox", { name: "Reason" })).toBeNull());
	},
};

export const MarkIncorrectFails: Story = {
	args: {
		onMark: fn(async () => {
			throw new Error("409");
		}),
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Mark incorrect" }));
		const popover = await screen.findByRole("dialog", {
			name: "Mark this observation incorrect",
		});
		const reason = within(popover).getByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		await userEvent.type(reason, "The 404 comes from the router.");
		await userEvent.click(within(popover).getByRole("button", { name: "Mark incorrect" }));
		await expect(args.onMark).toHaveBeenCalledOnce();
		await expect(within(popover).getByRole("textbox", { name: "Reason" })).toHaveValue(
			"The 404 comes from the router.",
		);
	},
};

export const MarkedIncorrect: Story = {
	args: { result: ready(markedIncorrect) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		const [chip, ...others] = panel.getAllByText("Marked incorrect", {
			selector: "[data-sheet-status] *",
		});
		if (!chip) {
			throw new Error("The level does not say it was marked incorrect");
		}
		await expect(others).toHaveLength(0);
		await expect(precedes(panel.getByRole("heading", { level: 2 }), chip)).toBe(true);
		await expect(
			panel.getByText(/Its pending feedback stops and posted comments on the work are retracted/u),
		).toBeVisible();
		await expect(panel.getByRole("button", { name: "Remove mark" })).toBeEnabled();
		await expect(panel.queryByRole("button", { name: "Mark incorrect" })).not.toBeInTheDocument();
		await expect(panel.queryByRole("button", { name: "Mark accepted" })).not.toBeInTheDocument();
		const history = within(panel.getByRole("list", { name: "History" }));
		await expect(history.getAllByRole("listitem").map((entry) => entry.textContent)).toEqual([
			expect.stringMatching(/^Recorded/u),
			expect.stringMatching(/^Marked incorrect.*by Ada Admin/u),
			expect.stringMatching(/^Mark removed.*by an account that no longer exists/u),
			expect.stringMatching(/^Marked incorrect.*by Ada Admin/u),
		]);
	},
};

export const RemoveIncorrectMark: Story = {
	args: { result: ready(markedIncorrect) },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Remove mark" }));
		const popover = await screen.findByRole("dialog", { name: "Remove the incorrect mark" });
		await expectSettledVisible(
			within(popover).getByText(
				/Feedback that was stopped stays stopped, and nothing is sent again/u,
			),
		);
		const reason = within(popover).getByRole("textbox", { name: "Reason" });
		await userEvent.type(reason, "The router does return the cache's 404.");
		await userEvent.click(within(popover).getByRole("button", { name: "Remove mark" }));
		await expect(args.onRemoveMark).toHaveBeenCalledWith(
			"mark-2",
			"The router does return the cache's 404.",
		);
	},
};

export const MarkedAccepted: Story = {
	args: { result: ready(markedAccepted) },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		within(panel.getByRole("list", { name: "History" })).getByText("Marked accepted");
		await expect(panel.getByRole("button", { name: "Mark incorrect" })).toBeEnabled();
		await expect(panel.queryByRole("button", { name: "Mark accepted" })).not.toBeInTheDocument();
		await userEvent.click(panel.getByRole("button", { name: "Remove mark" }));
		const popover = await screen.findByRole("dialog", { name: "Remove the accepted mark" });
		const reason = within(popover).getByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		await userEvent.type(reason, "Accepted by mistake.");
		await userEvent.click(within(popover).getByRole("button", { name: "Remove mark" }));
		await expect(args.onRemoveMark).toHaveBeenCalledWith("mark-3", "Accepted by mistake.");
	},
};

export const Marking: Story = {
	args: { isMarking: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Mark incorrect" }));
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Mark accepted" }));
	},
};

export const Loading: Story = {
	args: { result: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveAccessibleName(
			"Loading observation",
		);
		panel.getByRole("button", { name: "Practice reviews" });
		await expect(panel.queryByText("We could not load this observation")).not.toBeInTheDocument();
		await expect(panel.queryByRole("button", { name: "Mark incorrect" })).not.toBeInTheDocument();
	},
};

export const LoadFailed: Story = {
	args: {
		result: {
			status: "error",
			error: { status: 500 },
			onRetry: fn(),
		},
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("We could not load this observation")).toBeVisible();
		await expect(screen.getByRole("dialog")).toHaveAccessibleName("Observation");
		await expect(panel.queryByRole("button", { name: "Mark incorrect" })).not.toBeInTheDocument();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

// Source denial must not imply that no evidence was captured.
export const TextUnavailable: Story = {
	args: {
		result: ready({
			...reviewResultDetail,
			summary: null,
			explanation: null,
			evidence: null,
			problems: [],
			evidenceHidden: true,
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.getByRole("heading", { name: "Observation text unavailable", level: 2 }),
		).toBeVisible();
		await expect(panel.getByText(RESULT_WORDS_UNAVAILABLE)).toBeVisible();
		await expect(panel.queryByText(/Nothing was quoted/u)).not.toBeInTheDocument();
		panel.getByText(reviewResultDetail.practiceName);
	},
};

export const HiddenProblemEvidence: Story = {
	args: {
		result: ready({
			...reviewResultDetail,
			evidenceHidden: true,
			evidence: null,
			problems: reviewResultDetail.problems.map((problem) => ({
				...problem,
				summary: "Evidence hidden",
				explanation: null,
				citations: [],
			})),
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getAllByText(
			"Quoted evidence is hidden because access to its sources could not be verified.",
		);
		await expect(panel.queryByText(/Nothing was quoted/u)).not.toBeInTheDocument();
	},
};

const WITHHELD_ID = "f0000000-0000-4000-8000-000000000001";
const CONVERSATION_ID = "f0000000-0000-4000-8000-000000000002";
const WITHDRAWN_ID = "f0000000-0000-4000-8000-000000000003";
const WITHDRAWN_READY_ID = "f0000000-0000-4000-8000-000000000004";

/**
 * The feedback written from the result, each named by where it went and how far it got, and each
 * opening its own sheet in this one's place. A withdrawn piece says so: one withdrawn after delivery
 * keeps its receipt and gains the flag, one withdrawn while ready is no longer offered as ready.
 */
export const WithItsFeedback: Story = {
	args: {
		result: ready({
			...reviewResultDetail,
			feedback: [
				{
					id: WITHHELD_ID,
					destination: "ON_WORK",
					state: "WITHHELD",
					deliveredAt: null,
					withdrawn: false,
				},
				{
					id: CONVERSATION_ID,
					destination: "CONVERSATION",
					state: "DELIVERED",
					deliveredAt: hoursBefore(2),
					withdrawn: false,
				},
				{
					id: WITHDRAWN_ID,
					destination: "PRACTICE_PAGE",
					state: "DELIVERED",
					deliveredAt: daysBefore(1),
					withdrawn: true,
				},
				{
					id: WITHDRAWN_READY_ID,
					destination: "CONVERSATION",
					state: "READY",
					deliveredAt: null,
					withdrawn: true,
				},
			],
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const list = within(
			panel.getByRole("list", { name: "Feedback written from this observation" }),
		);
		await expect(list.getAllByRole("listitem")).toHaveLength(4);
		const conversation = deliveryOutcome({
			destination: "CONVERSATION",
			state: "DELIVERED",
			stopReason: null,
			withdrawn: false,
		});
		for (const [name, id] of [
			[
				`${FEEDBACK_DESTINATION_DEFS.ON_WORK.label}, ${DELIVERY_STATE_DEFS.WITHHELD.label}`,
				WITHHELD_ID,
			],
			[`${FEEDBACK_DESTINATION_DEFS.CONVERSATION.label}, ${conversation.label}`, CONVERSATION_ID],
			[
				`${FEEDBACK_DESTINATION_DEFS.PRACTICE_PAGE.label}, ${DELIVERY_STATE_DEFS.DELIVERED.label}, withdrawn`,
				WITHDRAWN_ID,
			],
			[
				`${FEEDBACK_DESTINATION_DEFS.CONVERSATION.label}, Ready, then withdrawn`,
				WITHDRAWN_READY_ID,
			],
		] as const) {
			await expect(levelsOpenedBy(list.getByRole("link", { name }))).toEqual([`feedback:${id}`]);
		}
		// The withdrawn ready piece names its withdrawal in its outcome, so only the delivered one gets the flag.
		await expect(list.getAllByText(FEEDBACK_DECISION_DEFS.WITHDRAW.label)).toHaveLength(1);
		await expect(list.queryByText("Ready for conversation")).toBeNull();
	},
};

/** A result whose record lists no feedback has no feedback section, and claims none. */
export const WithoutFeedback: Story = {
	args: { result: ready({ ...reviewResultDetail, feedback: [] }) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("heading", { name: "Feedback", level: 4 })).toBeNull();
		await expect(
			panel.queryByRole("list", { name: "Feedback written from this observation" }),
		).toBeNull();
	},
};
