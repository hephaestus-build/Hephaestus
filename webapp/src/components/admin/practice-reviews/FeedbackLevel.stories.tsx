import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type {
	FeedbackBasis,
	FeedbackCopy,
	FeedbackSend,
	ReviewFeedbackDetail,
} from "@/api/types.gen";
import { FEEDBACK_LOCATION_DEFS } from "@/components/practice-vocabulary/feedback-location-defs";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import {
	expectSectionHeadings,
	expectSectionLines,
	expectSectionsFold,
} from "@/stories/section-lines";
import { expectGenuinelyDisabled } from "@/test/controls";
import { levelsOpenedBy } from "@/test/detail-stack";
import { precedes } from "@/test/dom";

import { FeedbackLevel } from "./FeedbackLevel";
import {
	approvedFeedbackDetail,
	awaitingApprovalFeedbackDetail,
	failedFeedbackDetail,
	feedbackDetail,
	longFeedbackDetail,
	markedIncorrectFeedbackDetail,
	partialPackageFeedbackDetail,
	POSTED_SUMMARY_COMMENT,
	PRIVATE_FEEDBACK_TEXT,
	rejectedFeedbackDetail,
	replacedFeedbackDetail,
	restoredFeedbackDetail,
	reviewFeedbackDetail,
	unconfirmedFeedbackDetail,
	uncertainWriteFeedbackDetail,
	withdrawnFeedbackDetail,
} from "./fixtures";
import { forgetLevelFolds } from "./level-folds";
import { SHEET_SECTIONS } from "./RecordSheet";
import { resultSummary } from "./result-words";
import { developerLabel } from "./review-format";
import { feedbackLevel } from "./review-levels";

const ready = (feedback: ReviewFeedbackDetail) => ({ status: "ready" as const, feedback });

/** A card shown on the developer's practice pages; its text is theirs alone. */
const practicePageCard = feedbackDetail("44444444-5555-5555-5555-555555555555");

const WITHDRAWN_AT = new Date("2026-07-28T16:10:00Z");

/**
 * Feedback whose summary and line notes were posted on the work and then withdrawn. The summary was
 * retracted. `lines` is what the removal left on each line note, and `send` is the removal itself.
 */
function withdrawnFromWork(
	lines: Pick<FeedbackCopy, "state" | "readback" | "lastError">,
	send: Pick<FeedbackSend, "state" | "attemptCount" | "lastError">,
): ReviewFeedbackDetail {
	const summary = longFeedbackDetail.copies.find((copy) => copy.kind === "SUMMARY");
	const copies = longFeedbackDetail.copies.map((copy): FeedbackCopy => {
		const posted = {
			...copy,
			postedAt: summary?.postedAt ?? WITHDRAWN_AT,
			externalRef: String(2_481_933 + copy.position),
			stopReason: null,
		};
		return copy.kind === "SUMMARY"
			? { ...posted, state: "RETRACTED", readback: null, lastError: null }
			: { ...posted, ...lines };
	});
	const count = (state: FeedbackCopy["state"]) =>
		copies.filter((copy) => copy.state === state).length;
	return {
		...longFeedbackDetail,
		withdrawn: true,
		copies,
		copyCounts: {
			planned: 0,
			writeStarted: 0,
			posted: count("POSTED"),
			failed: 0,
			withheld: 0,
			retracted: count("RETRACTED"),
		},
		decisions: withdrawnFeedbackDetail.decisions.map((decision) => ({
			...decision,
			feedbackId: longFeedbackDetail.id,
		})),
		sends: [
			{
				feedbackId: longFeedbackDetail.id,
				action: "RETRACT",
				createdAt: WITHDRAWN_AT,
				updatedAt: WITHDRAWN_AT,
				nextAttemptAt: WITHDRAWN_AT,
				...send,
			},
		],
	};
}

/**
 * Feedback whose summary write on the work the provider never confirmed. `send` is where its post
 * stands, and `withdrawn` adds an admin's withdrawal, which queues no removal while the write is
 * unknown.
 */
function unknownWriteOnWork(
	send: FeedbackSend["state"],
	{ withdrawn = false }: { withdrawn?: boolean } = {},
): ReviewFeedbackDetail {
	const { id } = uncertainWriteFeedbackDetail;
	return {
		...uncertainWriteFeedbackDetail,
		state: "UNCONFIRMED",
		stopReason: "DELIVERY_UNCONFIRMED",
		withdrawn,
		copies: uncertainWriteFeedbackDetail.copies.map((copy) => ({
			...copy,
			readback: "UNKNOWN" as const,
			stopReason: "DELIVERY_UNCONFIRMED" as const,
			lastError: "Provider visibility is unknown.",
		})),
		sends: uncertainWriteFeedbackDetail.sends.map((post) => ({
			...post,
			state: send,
			attemptCount: 8,
			lastError: "Provider visibility is unknown.",
		})),
		decisions: withdrawn
			? withdrawnFeedbackDetail.decisions.map((decision) => ({ ...decision, feedbackId: id }))
			: [],
	};
}

const UNKNOWN_ON_WORK_NOW =
	"A comment was being written to the work, but the provider has not confirmed whether it appeared. It does not count as delivered until that is known. Until then, later reviews post no new feedback for this developer on this work.";

/**
 * A package whose summary was posted while its line notes' writes stayed unknown. A retraction
 * means it was withdrawn: the summary is removed, and the notes, which have no provider reference,
 * are skipped. `send` is the feedback's one send.
 */
function partlyUnknownOnWork(
	send: Pick<FeedbackSend, "action" | "state" | "attemptCount">,
): ReviewFeedbackDetail {
	const withdrawn = send.action === "RETRACT";
	const copies = longFeedbackDetail.copies.map((copy): FeedbackCopy =>
		copy.kind === "SUMMARY"
			? {
					...copy,
					state: withdrawn ? "RETRACTED" : "POSTED",
					postedAt: copy.postedAt ?? WITHDRAWN_AT,
					externalRef: "2481933",
					readback: null,
					stopReason: null,
					lastError: null,
				}
			: {
					...copy,
					state: "WRITE_STARTED",
					postedAt: null,
					externalRef: null,
					readback: "UNKNOWN",
					stopReason: "DELIVERY_UNCONFIRMED",
					lastError: "Provider visibility is unknown.",
				},
	);
	return {
		...longFeedbackDetail,
		destination: "ON_WORK",
		state: "UNCONFIRMED",
		stopReason: "DELIVERY_UNCONFIRMED",
		withdrawn,
		copies,
		copyCounts: {
			planned: 0,
			writeStarted: copies.length - 1,
			posted: withdrawn ? 0 : 1,
			failed: 0,
			withheld: 0,
			retracted: withdrawn ? 1 : 0,
		},
		decisions: withdrawn
			? withdrawnFeedbackDetail.decisions.map((decision) => ({
					...decision,
					feedbackId: longFeedbackDetail.id,
				}))
			: [],
		sends: [
			{
				feedbackId: longFeedbackDetail.id,
				createdAt: WITHDRAWN_AT,
				updatedAt: WITHDRAWN_AT,
				nextAttemptAt: WITHDRAWN_AT,
				lastError: "Provider visibility is unknown.",
				...send,
			},
		],
	};
}

const UNACKNOWLEDGED_REMOVAL = {
	state: "POSTED",
	readback: "TRANSIENT",
	lastError: "Provider correction is not confirmed.",
} as const;

const meta = {
	component: FeedbackLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		feedback: ready(reviewFeedbackDetail),
		isDeciding: false,
		onApprove: fn(),
		onReject: fn(),
		onChangeWithdrawal: fn(async () => undefined),
		isChangingWithdrawal: false,
		onRetryWithdrawal: fn(),
		isRetryingWithdrawal: false,
		onCheckDelivery: fn(),
		isCheckingDelivery: false,
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={feedbackLevel(reviewFeedbackDetail.id)} path={args.path}>
			{(level) => <FeedbackLevel {...args} {...level} />}
		</InLevelStack>
	),
	// A fold is kept in this browser, so every story starts with all sections open.
	beforeEach: () => {
		forgetLevelFolds("feedback");
		return () => forgetLevelFolds("feedback");
	},
} satisfies Meta<typeof FeedbackLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A chip in the header: what became of the feedback, under the title, where standing goes. */
async function expectHeaderChip(panel: HTMLElement, label: string) {
	const [chip] = within(panel).getAllByText(label);
	if (!chip) {
		throw new Error(`No "${label}" on the level`);
	}
	await expect(precedes(within(panel).getByRole("heading", { level: 2 }), chip)).toBe(true);
}

/** The sheet's title: the developer and where the feedback goes, never its text. */
function titleOf(feedback: ReviewFeedbackDetail): string {
	const where = FEEDBACK_LOCATION_DEFS[feedback.location].label;
	return `For ${developerLabel(feedback.developer)} · ${where}`;
}

function history(panel: HTMLElement) {
	return within(within(panel).getByRole("list", { name: "History" }));
}

export const Withheld: Story = {
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		// Titled by who it is for and where it goes, as its row is named; its text is said once, below.
		const heading = panel.getByRole("heading", { level: 2 });
		await expect(heading).toHaveTextContent(titleOf(reviewFeedbackDetail));
		await expect(heading).not.toHaveTextContent(/public response/u);
		await expectHeaderChip(panelElement, "Withheld");
		const sections = [
			SHEET_SECTIONS.what,
			SHEET_SECTIONS.state,
			"What it says",
			"Checks",
			SHEET_SECTIONS.technical,
		];
		await expect(
			panel
				.getAllByRole("heading", { level: 3 })
				.map((sectionHeading) => sectionHeading.textContent)
				.filter((text) => sections.includes(text)),
		).toStrictEqual(sections);
		// A sheet links its own relatives only: the results and problems the feedback rests on. A
		// reference with nowhere to go has no address.
		for (const link of panel.queryAllByRole("link")) {
			await expect(String(link.getAttribute("href"))).toMatch(
				/^(?:https:\/\/|null$)|\?detail=(?:result|problem):[^&]+$/u,
			);
		}
		history(panelElement).getByText(
			"Found while reviewing past work, which is measured but never sent.",
		);
		await expect(
			panel.queryByRole("button", { name: "Approve for delivery" }),
		).not.toBeInTheDocument();
		await expect(
			panel.queryByRole("button", { name: "Withdraw feedback" }),
		).not.toBeInTheDocument();
	},
};

/** The level's default: delivered feedback, with its sections parted, at one size, and foldable. */
export const Delivered: Story = {
	args: { feedback: ready(approvedFeedbackDetail) },
	play: async ({ userEvent }) => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		// What it is, State and history, What it says, Based on, Checks, Technical.
		await expectSectionLines(panelElement, 5);
		await expectSectionHeadings(panelElement);
		await expectSectionsFold(panelElement, userEvent);
		await expectHeaderChip(panelElement, "Delivered");
		// The section is called "Checks", so the box under it says no title of its own, and its steps
		// sit one level below the section.
		const checks = within(panel.getByRole("region", { name: "Checks" }));
		await expect(checks.queryByRole("heading", { name: "Feedback checks" })).toBeNull();
		await expect(checks.getAllByRole("heading", { level: 4 }).length).toBeGreaterThan(0);
		const events = history(panelElement);
		events.getByText("Approved");
		events.getByText("by Grace Hopper");
		events.getByText("Summary posted");
		await expect(events.getByRole("link", { name: /Open the comment/u })).toHaveAttribute(
			"href",
			expect.stringContaining("#issuecomment-"),
		);
		await expect(events.queryByText("Done")).toBeNull();
		events.getByText("Answered");
		events.getByText("Helpful");
		// Where it went, by provider: once, among What it is.
		panel.getByText(/^Comment on GitHub pull request/u);
		await expect(panel.queryByRole("button", { name: "Reject feedback" })).not.toBeInTheDocument();
		await expect(panel.getByRole("button", { name: "Withdraw feedback" })).toBeEnabled();
	},
};

/**
 * A summary comment on the pull request: "What it says" shows the posted text as the developer saw
 * it on GitHub, without the hidden marker and with the footer as small print.
 */
export const DeliveredSummaryComment: Story = {
	args: {
		feedback: ready({
			...approvedFeedbackDetail,
			destination: "ON_WORK",
			body: POSTED_SUMMARY_COMMENT,
			copies: approvedFeedbackDetail.copies.map((copy) => ({
				...copy,
				body: POSTED_SUMMARY_COMMENT,
			})),
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const rendered = panel.getByRole("tabpanel", { name: "Rendered" });
		await expect(rendered.textContent).toContain("The description needs its purpose and coverage.");
		await expect(rendered.textContent).not.toMatch(/<!--|<sub>/u);
		await expect(
			within(rendered).getByRole("link", {
				name: "Why you see this and how to stop it (opens in a new tab)",
			}),
		).toBeVisible();
	},
};

export const PartialPackage: Story = {
	args: { feedback: ready(partialPackageFeedbackDetail) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent(
			/1 posted · 2 failed/u,
		);
		const events = history(panelElement);
		events.getByText("Summary posted");
		await expect(events.getAllByText("Line note failed")).toHaveLength(2);
		events.getByText("ReviewQueryService.java:116–119");
		await expect(events.getAllByText(/the line is no longer part of the diff/u)).toHaveLength(2);
		events.getByText("Waiting to be sent");
		events.getByText("Post the copies · 2 attempts");
		events.getByText(/Next attempt/u);
		events.getByText(/422 for both line notes/u);
	},
};

export const Disagreed: Story = {
	args: { feedback: ready({ ...longFeedbackDetail, destination: "ON_WORK" }) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expectHeaderChip(panelElement, "Disagreed");
		const events = history(panelElement);
		events.getByText("Disagreed");
		events.getByText("The two cases share a cause, so one status is right here.");
		await expect(events.queryByText(/Marked incorrect/u)).toBeNull();
		within(panelElement).getByText(/While the developer’s disagreement stands/u);
	},
};

export const ClearedResponse: Story = {
	args: {
		feedback: ready({
			...longFeedbackDetail,
			disagreedAt: null,
			response: { resolution: null, usefulness: null, comment: null, createdAt: null },
		}),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const events = history(panelElement);
		await expect(events.queryByText("Disagreed")).toBeNull();
		await expect(events.queryByText("Answered")).toBeNull();
		await expect(
			within(panelElement).queryByText(/While the developer’s disagreement stands/u),
		).toBeNull();
	},
};

// An uncertain provider write must be checked before another send.
export const UncertainWrite: Story = {
	args: { feedback: ready(uncertainWriteFeedbackDetail) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expectHeaderChip(panelElement, "Ready to send");
		const events = history(panelElement);
		events.getByText("Not confirmed");
		events.getByText("Read the work for the unconfirmed comment · 1 attempt");
		events.getByText("This check posts nothing.");
		events.getByText("Summary writing");
		events.getByText(/The connection closed before GitHub answered/u);
		within(panelElement).getByText(
			"A write was not confirmed. The provider is checked before anything is posted again.",
		);
	},
};

export const DeliveryFailed: Story = {
	args: { feedback: ready(failedFeedbackDetail) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expectHeaderChip(panelElement, "Failed to deliver");
		const events = history(panelElement);
		events.getByText("Stopped trying");
		events.getByText("Summary failed");
		events.getByText("Post the copies · 5 attempts");
		await expect(events.getAllByText(/Resource not accessible by integration/u).length).toBe(2);
		await expect(events.queryByText(/Next attempt/u)).toBeNull();
	},
};

export const WithheldResultMarkedIncorrect: Story = {
	args: { feedback: ready(markedIncorrectFeedbackDetail) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expectHeaderChip(panelElement, "Withheld");
		history(panelElement).getByText(
			"A workspace admin marked an observation behind it as incorrect, so nothing more of it was sent.",
		);
	},
};

export const WithheldAfterTheWorkChanged: Story = {
	args: {
		feedback: ready({
			...approvedFeedbackDetail,
			state: "WITHHELD",
			stopReason: "REVIEWED_VERSION_CHANGED",
			deliveredAt: null,
			copies: approvedFeedbackDetail.copies.map((copy) => ({
				...copy,
				state: "WITHHELD" as const,
				stopReason: "REVIEWED_VERSION_CHANGED" as const,
				postedAt: null,
				externalRef: null,
				externalUrl: null,
			})),
			sends: [],
			response: null,
		}),
	},
	play: async () => {
		const events = history(await settledDrawerPanel());
		await expect(
			events.getAllByText("The work changed before this feedback could be posted."),
		).toHaveLength(1);
		events.getByText("Summary withheld");
	},
};

export const Replaced: Story = {
	args: { feedback: ready(replacedFeedbackDetail) },
	play: async () => {
		await expectHeaderChip(await settledDrawerPanel(), "Replaced by newer");
	},
};

export const Rejected: Story = {
	args: { feedback: ready(rejectedFeedbackDetail) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expectHeaderChip(panelElement, "Rejected");
		const events = history(panelElement);
		events.getByText("Rejected");
		events.getByText("by Grace Hopper");
		await expect(events.getByText(/Not useful to the developer/u)).toBeVisible();
		events.getByText(/The long comment already covers the test names/u);
	},
};

export const DecidedByAnErasedAccount: Story = {
	args: {
		feedback: ready({
			...rejectedFeedbackDetail,
			decisions: rejectedFeedbackDetail.decisions.map((decision) => ({
				...decision,
				actor: null,
			})),
		}),
	},
	play: async () => {
		await expect(
			history(await settledDrawerPanel()).getByText(/an account that no longer exists/u),
		).toBeVisible();
	},
};

export const Withdrawn: Story = {
	args: { feedback: ready(withdrawnFeedbackDetail) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Withdrawn");
		panel.getByText(/It is off the developer’s practice pages/u);
		await expect(panel.queryByRole("alert")).toBeNull();
		await expect(panel.getByRole("button", { name: "Restore feedback" })).toBeEnabled();
		const events = history(panelElement);
		events.getByText("Withdrawn");
		events.getByText(/Already covered elsewhere/u);
	},
};

export const Restored: Story = {
	args: { feedback: ready(restoredFeedbackDetail) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const events = history(panelElement);
		events.getByText("Withdrawn");
		events.getByText("Restored");
		events.getByText(/The observation stands after all/u);
		await expect(
			within(panelElement).getByRole("button", { name: "Withdraw feedback" }),
		).toBeEnabled();
	},
};

/**
 * The removal stopped trying while the provider had not acknowledged removing the line notes. The
 * withdrawal stands, the receipts stay posted, and the admin can try the removal again.
 */
export const WithdrawalNotConfirmed: Story = {
	args: {
		feedback: ready(
			withdrawnFromWork(UNACKNOWLEDGED_REMOVAL, {
				state: "DONE",
				attemptCount: 8,
				lastError: "Provider visibility is unknown.",
			}),
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Delivered");
		await expectHeaderChip(panelElement, "Withdrawn");
		panel.getByText(
			/2 copies may still be on the work\. At least one removal is unconfirmed\. Check each copy above for details\./u,
		);
		const events = history(panelElement);
		events.getByText("Retraction incomplete");
		await expect(events.queryByText("Retracted")).toBeNull();
		await expect(events.getAllByText("Provider correction is not confirmed.")).toHaveLength(2);
		events.getByText("Provider visibility is unknown.");
		await expect(panel.getByRole("button", { name: "Retry withdrawal" })).toBeEnabled();
		await expect(panel.getByRole("button", { name: "Restore feedback" })).toBeEnabled();
	},
};

/** While the removal is still being tried, there is nothing to retry yet. */
export const WithdrawalInProgress: Story = {
	args: {
		feedback: ready(
			withdrawnFromWork(UNACKNOWLEDGED_REMOVAL, {
				state: "UNCERTAIN",
				attemptCount: 3,
				lastError: "Provider visibility is unknown.",
			}),
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText(/Heph is still removing the copies it posted on the work\./u);
		history(panelElement).getByText("Not confirmed");
		// A removal keeps its own words: it is not a readback.
		history(panelElement).getByText("Retract the posted copies · 3 attempts");
		await expect(panel.queryByRole("button", { name: "Retry withdrawal" })).toBeNull();
		await expect(panel.getByRole("button", { name: "Restore feedback" })).toBeEnabled();
	},
};

/**
 * The retraction settled: it removed the summary and could not reach the notes, whose writes are
 * unknown. Only reading the work again can settle them, so the check is offered on the withdrawal.
 */
export const WithdrawnPartlyRetractedUnknownWrite: Story = {
	args: {
		feedback: ready(partlyUnknownOnWork({ action: "RETRACT", state: "DONE", attemptCount: 8 })),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Withdrawn");
		panel.getByText(
			"It is withdrawn, but 2 copies may still be on the work, because a write was never confirmed. Check delivery to find out. A comment found there is then retracted. The observations behind it are unchanged.",
		);
		await expect(panel.queryByText(/Heph cannot remove/u)).toBeNull();
		const events = history(panelElement);
		events.getByText("Summary posted");
		await expect(events.getAllByText("Line note writing")).toHaveLength(2);
		events.getByText("Retraction incomplete");
		events.getByText("Retract the posted copies · 8 attempts");
		await expect(panel.getByRole("button", { name: "Check delivery" })).toBeEnabled();
		await expect(panel.queryByRole("button", { name: "Retry withdrawal" })).toBeNull();
		await expect(panel.getByRole("button", { name: "Restore feedback" })).toBeEnabled();
	},
};

const partlyRetracted = partlyUnknownOnWork({ action: "RETRACT", state: "DONE", attemptCount: 8 });

/**
 * The retraction settled with the summary's removal unacknowledged and the notes unknown, so both
 * recoveries apply: retrying the removal, and reading the work for the notes.
 */
const RETRACTABLE_AND_UNKNOWN: ReviewFeedbackDetail = {
	...partlyRetracted,
	copies: partlyRetracted.copies.map((copy) =>
		copy.kind === "SUMMARY" ? { ...copy, ...UNACKNOWLEDGED_REMOVAL } : copy,
	),
	copyCounts: { ...partlyRetracted.copyCounts, posted: 1, retracted: 0 },
};

/** At rest, both recoveries are offered. */
export const WithdrawnRetractableAndUnknown: Story = {
	args: { feedback: ready(RETRACTABLE_AND_UNKNOWN) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("button", { name: "Retry withdrawal" })).toBeEnabled();
		await expect(panel.getByRole("button", { name: "Check delivery" })).toBeEnabled();
		await expect(panel.getByRole("button", { name: "Restore feedback" })).toBeEnabled();
	},
};

/** A check in flight holds the retry too: both queue the feedback's one send. */
export const CheckingWhileRetractable: Story = {
	args: { feedback: ready(RETRACTABLE_AND_UNKNOWN), isCheckingDelivery: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Check delivery" }));
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Retry withdrawal" }));
	},
};

/** A retry in flight holds the check the same way. */
export const RetryingWithdrawalWhileUnknown: Story = {
	args: { feedback: ready(RETRACTABLE_AND_UNKNOWN), isRetryingWithdrawal: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Retry withdrawal" }));
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Check delivery" }));
	},
};

/** While that retraction still runs, it is a removal, not a check, and no check is offered yet. */
export const WithdrawnPartlyRetractedRemovalInProgress: Story = {
	args: {
		feedback: ready(
			partlyUnknownOnWork({ action: "RETRACT", state: "UNCERTAIN", attemptCount: 3 }),
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText(/Heph is still removing the copies it posted on the work\./u);
		const events = history(panelElement);
		events.getByText("Not confirmed");
		events.getByText("Retract the posted copies · 3 attempts");
		await expect(
			events.queryByText("Read the work for the unconfirmed comment · 3 attempts"),
		).toBeNull();
		await expect(panel.queryByRole("button", { name: "Check delivery" })).toBeNull();
	},
};

/** Every posted copy has a receipt for its removal. */
export const WithdrawalComplete: Story = {
	args: {
		feedback: ready(
			withdrawnFromWork(
				{ state: "RETRACTED", readback: null, lastError: null },
				{ state: "DONE", attemptCount: 2, lastError: null },
			),
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText(/Copies it posted on the work are retracted there/u);
		const events = history(panelElement);
		events.getByText("Retracted");
		await expect(events.queryByText("Retraction incomplete")).toBeNull();
		await expect(panel.queryByRole("button", { name: "Retry withdrawal" })).toBeNull();
	},
};

/** The provider cannot correct the line notes, so a retry would not remove them either. */
export const WithdrawalNeedsAttention: Story = {
	args: {
		feedback: ready(
			withdrawnFromWork(
				{
					state: "POSTED",
					readback: null,
					lastError: "This provider cannot correct the posted copy.",
				},
				{ state: "DONE", attemptCount: 2, lastError: null },
			),
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText(/2 copies may still be on the work, and Heph cannot remove them\./u);
		const events = history(panelElement);
		events.getByText("Retraction incomplete");
		await expect(events.getAllByText("This provider cannot correct the posted copy.")).toHaveLength(
			2,
		);
		await expect(panel.queryByRole("button", { name: "Retry withdrawal" })).toBeNull();
		await expect(panel.getByRole("button", { name: "Restore feedback" })).toBeEnabled();
	},
};

/** The reason is required: a withdrawal nobody can explain later is not an audit. */
export const WithdrawFeedback: Story = {
	args: { feedback: ready(practicePageCard) },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, userEvent }) => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		// The text is private to the developer, and is not reported as never composed.
		panel.getByText(/written for the developer’s own practice pages and is withheld from admins/u);
		await expect(panelElement.textContent).not.toContain(PRIVATE_FEEDBACK_TEXT);
		panel.getByText("Card on their practice pages");
		await userEvent.click(panel.getByRole("button", { name: "Withdraw feedback" }));
		const reason = await screen.findByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		const submit = screen.getAllByRole("button", { name: "Withdraw feedback" }).at(-1);
		await expect(submit).toBeDisabled();
		await userEvent.type(reason, "  It describes issues from before this pull request.  ");
		if (submit) {
			await userEvent.click(submit);
		}
		await expect(args.onChangeWithdrawal).toHaveBeenCalledWith(
			true,
			"It describes issues from before this pull request.",
		);
		await waitFor(async () => expect(screen.queryByRole("textbox", { name: "Reason" })).toBeNull());
	},
};

const UNKNOWN_WRITE_WITHDRAWAL =
	"An unconfirmed comment may still be on the work. Heph can retract that comment only if a check finds it. Receipts and observations are kept.";

/** Withdrawing a comment whose write is unknown promises no removal it cannot make yet. */
export const WithdrawUnconfirmedComment: Story = {
	args: { feedback: ready(unknownWriteOnWork("DONE")) },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Withdraw feedback" }));
		await expectSettledVisible(await screen.findByRole("textbox", { name: "Reason" }));
		screen.getByText(UNKNOWN_WRITE_WITHDRAWAL);
		await expect(
			screen.queryByText(/^Copies already posted on the work are retracted there, and/u),
		).toBeNull();
	},
};

/** Withdrawing a package with a posted summary and unknown notes says what happens to each. */
export const WithdrawPartlyPostedUnconfirmed: Story = {
	args: {
		feedback: ready(partlyUnknownOnWork({ action: "POST", state: "DONE", attemptCount: 8 })),
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("button", { name: "Check delivery" })).toBeEnabled();
		await userEvent.click(panel.getByRole("button", { name: "Withdraw feedback" }));
		await expectSettledVisible(await screen.findByRole("textbox", { name: "Reason" }));
		screen.getByText(
			`Copies already posted on the work are retracted there. ${UNKNOWN_WRITE_WITHDRAWAL}`,
		);
	},
};

/** Withdrawing feedback whose copies are all accounted for keeps the plain promise. */
export const WithdrawDeliveredFromWork: Story = {
	args: { feedback: ready({ ...approvedFeedbackDetail, destination: "ON_WORK" }) },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Withdraw feedback" }));
		await expectSettledVisible(await screen.findByRole("textbox", { name: "Reason" }));
		screen.getByText(
			"Copies already posted on the work are retracted there, and nothing more is posted. Their receipts stay on this sheet, and the observations behind it are unchanged.",
		);
		await expect(screen.queryByText(UNKNOWN_WRITE_WITHDRAWAL)).toBeNull();
	},
};

/** A refused withdrawal keeps the form open with the reason the admin wrote, ready for another try. */
export const WithdrawFeedbackFails: Story = {
	args: {
		feedback: ready(practicePageCard),
		onChangeWithdrawal: fn(async () => {
			throw new Error("409");
		}),
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Withdraw feedback" }));
		const reason = await screen.findByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		await userEvent.type(reason, "It describes older issues.");
		const submit = screen.getAllByRole("button", { name: "Withdraw feedback" }).at(-1);
		if (submit) {
			await userEvent.click(submit);
		}
		await expect(args.onChangeWithdrawal).toHaveBeenCalledOnce();
		await expect(screen.getByRole("textbox", { name: "Reason" })).toHaveValue(
			"It describes older issues.",
		);
	},
};

// Without an appearance receipt, a conversation link cannot establish delivery.
export const DeliveryUnconfirmed: Story = {
	args: { feedback: ready(unconfirmedFeedbackDetail) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Delivery unconfirmed");
		panel.getByText(
			"A conversation linked it, but its appearance was not recorded, so it does not count as delivered.",
		);
		await expect(history(panelElement).queryByText("Delivered")).toBeNull();
		// A conversation has nothing on the work to read again.
		await expect(panel.queryByRole("button", { name: "Check delivery" })).toBeNull();
	},
};

/**
 * The post stopped trying while the provider never confirmed whether the summary appeared. Reading
 * the work again is offered, not another write.
 */
export const DeliveryUnknownOnWork: Story = {
	args: { feedback: ready(unknownWriteOnWork("DONE")) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Delivery unconfirmed");
		panel.getByText(UNKNOWN_ON_WORK_NOW);
		await expect(panel.queryByText(/conversation linked/iu)).toBeNull();
		history(panelElement).getByText("Summary writing");
		await expect(panel.getByRole("button", { name: "Check delivery" })).toBeEnabled();
		await expect(panel.queryByRole("button", { name: "Retry withdrawal" })).toBeNull();
		await expect(panel.getByRole("button", { name: "Withdraw feedback" })).toBeEnabled();
	},
};

/** A check already asked for cannot be asked for twice. */
export const CheckingDelivery: Story = {
	args: { feedback: ready(unknownWriteOnWork("DONE")), isCheckingDelivery: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Check delivery" }));
	},
};

/** While the post reads the work again, the sheet says so and offers no second check. */
export const DeliveryCheckInProgress: Story = {
	args: { feedback: ready(unknownWriteOnWork("PENDING")) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText(
			"Heph is confirming whether a comment it began writing appeared on the work. Until the provider confirms it, it does not count as delivered.",
		);
		await expect(panel.queryByText(UNKNOWN_ON_WORK_NOW)).toBeNull();
		// The queued post only reads the work back; it is not a send waiting to post.
		const events = history(panelElement);
		events.getByText("Waiting to check");
		events.getByText("Read the work for the unconfirmed comment · 8 attempts");
		events.getByText("This check posts nothing.");
		await expect(events.queryByText("Waiting to be sent")).toBeNull();
		await expect(events.queryByText(/Post the copies/u)).toBeNull();
		await expect(panel.queryByRole("button", { name: "Check delivery" })).toBeNull();
	},
};

/** The check proved the summary absent: it is a failed copy, not an unknown one, and is not checked again. */
export const DeliveryKnownAbsent: Story = {
	args: {
		feedback: ready({
			...unknownWriteOnWork("DONE"),
			state: "FAILED",
			stopReason: null,
			copies: uncertainWriteFeedbackDetail.copies.map((copy) => ({
				...copy,
				state: "FAILED" as const,
				readback: "ABSENT" as const,
				lastError: "Provider readback proved the copy is absent.",
			})),
		}),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Failed to deliver");
		const events = history(panelElement);
		events.getByText("Summary failed");
		events.getByText("Provider readback proved the copy is absent.");
		await expect(panel.queryByText(/has not confirmed whether it appeared/u)).toBeNull();
		await expect(panel.queryByRole("button", { name: "Check delivery" })).toBeNull();
	},
};

/**
 * Withdrawn while its write was unknown: nothing can be removed until the work is read again, so a
 * check is offered in place of a removal that cannot run.
 */
export const WithdrawnUnknownWrite: Story = {
	args: { feedback: ready(unknownWriteOnWork("DONE", { withdrawn: true })) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Withdrawn");
		panel.getByText(
			"It is withdrawn, but one copy may still be on the work, because a write was never confirmed. Check delivery to find out. A comment found there is then retracted. The observations behind it are unchanged.",
		);
		await expect(panel.queryByText(/Heph cannot remove/u)).toBeNull();
		await expect(panel.getByRole("button", { name: "Check delivery" })).toBeEnabled();
		await expect(panel.queryByRole("button", { name: "Retry withdrawal" })).toBeNull();
		await expect(panel.getByRole("button", { name: "Restore feedback" })).toBeEnabled();
	},
};

/** A withdrawn piece being checked is not reported as being removed. */
export const WithdrawnDeliveryCheckInProgress: Story = {
	args: { feedback: ready(unknownWriteOnWork("SENDING", { withdrawn: true })) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText(
			"It is withdrawn. Heph is confirming whether a comment it began writing appeared on the work. A comment found there is then retracted. The observations behind it are unchanged.",
		);
		await expect(panel.queryByText(/still removing/u)).toBeNull();
		// Being sent, it may be the first write or a readback; the record does not say which.
		const events = history(panelElement);
		events.getByText("Writing or checking");
		events.getByText("Write the comment, or read back the one already started · 8 attempts");
		events.getByText("The record does not say which.");
		await expect(panel.queryByRole("button", { name: "Check delivery" })).toBeNull();
	},
};

export const ReadyForConversation: Story = {
	args: { feedback: ready(feedbackDetail("11111111-4444-4444-4444-444444444444")) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expectHeaderChip(panelElement, "Ready for conversation");
		within(panelElement).getByText("For a conversation with Heph");
		within(panelElement).getByText(/which is what delivers it/u);
	},
};

/**
 * Withdrawn while it waited for a conversation: the stored state stays ready, but Heph does not
 * raise it, so the sheet does not promise the next conversation and names the withdrawal once.
 */
export const WithdrawnBeforeConversation: Story = {
	args: {
		feedback: ready({
			...feedbackDetail("11111111-4444-4444-4444-444444444444"),
			withdrawn: true,
			decisions: withdrawnFeedbackDetail.decisions.map((decision) => ({
				...decision,
				feedbackId: "11111111-4444-4444-4444-444444444444",
			})),
		}),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Ready, then withdrawn");
		await expect(panelElement.querySelector("[data-sheet-status]")).not.toHaveTextContent(
			/Withdrawn|Ready for conversation/u,
		);
		panel.getByText(
			"It is withdrawn, so Heph does not raise it in a conversation. The observations behind it are unchanged.",
		);
		await expect(panel.queryByText(/which is what delivers it/u)).toBeNull();
		await expect(panel.getByRole("button", { name: "Restore feedback" })).toBeEnabled();
	},
};

export const ReadyForPracticePages: Story = {
	args: {
		feedback: ready({ ...practicePageCard, state: "READY", deliveredAt: null }),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expectHeaderChip(panelElement, "Ready for their practice pages");
		await expect(
			within(panelElement).getByText(/counts as delivered when they open it/u),
		).toBeVisible();
		await expect(within(panelElement).queryByText(/tried again/u)).toBeNull();
	},
};

export const Reflow: Story = {
	args: { feedback: ready({ ...longFeedbackDetail, destination: "ON_WORK" }) },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

/** Long feedback reads whole, Rendered or as its Source. */
export const LongFeedback: Story = {
	args: { feedback: ready({ ...longFeedbackDetail, destination: "ON_WORK" }) },
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		const rendered = panel.getByRole("tabpanel", { name: "Rendered" });
		await expect(within(rendered).getByText(/2 issues to tighten in this change/u)).toBeVisible();
		await userEvent.click(panel.getByRole("tab", { name: "Source" }));
		await expect(panel.getByRole("tabpanel", { name: "Source" }).textContent).toContain("```java");
	},
};

export const AwaitingApproval: Story = {
	args: { feedback: ready(awaitingApprovalFeedbackDetail) },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText(/Approval authorizes every copy above/u)).toBeVisible();
		await expect(panel.getByText(/^A summary, a line note,? and a file note$/u)).toBeVisible();
		await expect(
			panel.getByText(/This name says the delay doubles, which is the rule a reader needs/u),
		).toBeVisible();
		panel.getByText("It waits for a workspace admin to approve or reject it.");

		// The decision is the footer, like every sheet's actions, so it stays in reach under the package.
		const approve = panel.getByRole("button", { name: "Approve for delivery" });
		const reject = panel.getByRole("button", { name: "Reject feedback" });
		await expect(approve.closest('[data-slot="drawer-footer"]')).not.toBeNull();
		await expect(reject.closest('[data-slot="drawer-footer"]')).not.toBeNull();
		await userEvent.click(approve);
		await expect(args.onApprove).toHaveBeenCalledOnce();
	},
};

export const AwaitingApprovalReflow: Story = {
	args: { feedback: ready(awaitingApprovalFeedbackDetail) },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(within(panel).getByRole("button", { name: "Approve for delivery" })).toBeVisible();
		await expectNoPanelOverflow(panel);
	},
};

const PREVIOUS_ID = "aaaaaaaa-0000-4000-8000-000000000001";
const NEXT_ID = "aaaaaaaa-0000-4000-8000-000000000003";

/**
 * Opened from the approval queue: where it sits among all awaiting approval, oldest first, a step
 * either way that swaps this level for its neighbour, and a decision that moves on to the next.
 */
export const InApprovalQueue: Story = {
	args: {
		feedback: ready(awaitingApprovalFeedbackDetail),
		queue: { position: 2, total: 7, previous: PREVIOUS_ID, next: NEXT_ID },
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		const steps = within(panel.getByRole("navigation", { name: "Feedback awaiting approval" }));
		steps.getByText("2 of 7");
		// A step replaces the level in front rather than stacking one over it.
		await expect(levelsOpenedBy(steps.getByRole("link", { name: "Previous" }))).toEqual([
			`feedback:${PREVIOUS_ID}`,
		]);
		await expect(levelsOpenedBy(steps.getByRole("link", { name: "Next" }))).toEqual([
			`feedback:${NEXT_ID}`,
		]);
		await userEvent.click(panel.getByRole("button", { name: "Approve and open next" }));
		await expect(args.onApprove).toHaveBeenCalledOnce();
	},
};

/**
 * The last in the queue, with no step forward — but the ones before it may have been skipped on the
 * way here and still wait, so a decision moves on to them and the button does not promise to close.
 */
export const LastInApprovalQueue: Story = {
	args: {
		feedback: ready(awaitingApprovalFeedbackDetail),
		queue: { position: 7, total: 7, previous: PREVIOUS_ID },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("7 of 7");
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Next" }));
		panel.getByRole("button", { name: "Approve and open next" });
	},
};

/** The only one awaiting approval: no steps to take, and approving it closes the level. */
export const OnlyInApprovalQueue: Story = {
	args: {
		feedback: ready(awaitingApprovalFeedbackDetail),
		queue: { position: 1, total: 1 },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.queryByRole("navigation", { name: "Feedback awaiting approval" }),
		).not.toBeInTheDocument();
		panel.getByRole("button", { name: "Approve and close" });
	},
};

/**
 * Whether `first` reads before `second` in a wrapping row: on a line above it, or on the same line
 * and to its left. How the footer wraps at 320px depends on the font's widths: with the system font
 * on macOS the steps take a line of their own, with Arial's widths they share one with "Reject
 * feedback", and either reads the steps first.
 */
function readsBefore(first: HTMLElement, second: HTMLElement): boolean {
	const a = first.getBoundingClientRect();
	const b = second.getBoundingClientRect();
	const SLACK_PX = 1;
	if (a.bottom <= b.top + SLACK_PX) {
		return true;
	}
	const sameLine = a.top < b.bottom && b.top < a.bottom;
	return sameLine && a.right <= b.left + SLACK_PX;
}

export const InApprovalQueueReflow: Story = {
	args: InApprovalQueue.args,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(
			within(panel).getByRole("button", { name: "Approve and open next" }),
		).toBeVisible();
		await expectNoPanelOverflow(panel);
		// The wrapped footer shows the steps first, as the tab order reaches them first.
		const steps = within(panel).getByRole("navigation", { name: "Feedback awaiting approval" });
		for (const name of ["Approve and open next", "Reject feedback"]) {
			await expect(
				readsBefore(steps, within(panel).getByRole("button", { name })),
				`The steps read after "${name}"`,
			).toBe(true);
		}
	},
};

export const PackageUnavailable: Story = {
	args: { feedback: ready({ ...awaitingApprovalFeedbackDetail, copies: [] }) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("alert")).toHaveTextContent("This review package is unavailable");
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Approve for delivery" }));
		await expect(panel.getByRole("button", { name: "Reject feedback" })).toBeEnabled();
	},
};

export const RejectingWithContext: Story = {
	args: { feedback: ready(awaitingApprovalFeedbackDetail) },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Reject feedback" }));
		const popover = await screen.findByRole("dialog", { name: "Reject this feedback" });
		const note = within(popover).getByLabelText("Note");
		await expectSettledVisible(note);
		// No category, no rejection: the reason is what the rejection is for.
		await expectGenuinelyDisabled(within(popover).getByRole("button", { name: "Reject feedback" }));
		await userEvent.click(within(popover).getByText("Missing important context"));
		await userEvent.type(note, "The fallback path is not represented in the review.");
		await userEvent.click(within(popover).getByRole("button", { name: "Reject feedback" }));
		await expect(args.onReject).toHaveBeenCalledWith(
			"MISSING_CONTEXT",
			"The fallback path is not represented in the review.",
		);
		await expect(args.onApprove).not.toHaveBeenCalled();
	},
};

export const Deciding: Story = {
	args: { feedback: ready(awaitingApprovalFeedbackDetail), isDeciding: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Approve for delivery" }));
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Reject feedback" }));
	},
};

/**
 * The drawer is named by its title, so the heading stands while the record loads; the path stays
 * usable, and no decision is offered about feedback not yet read.
 */
export const Loading: Story = {
	args: { feedback: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveAccessibleName("Loading feedback");
		panel.getByRole("button", { name: "Practice reviews" });
		await expect(panel.queryByText("We could not load this feedback")).not.toBeInTheDocument();
		await expect(
			panel.queryByRole("button", { name: "Approve for delivery" }),
		).not.toBeInTheDocument();
	},
};

export const LoadFailed: Story = {
	args: {
		feedback: {
			status: "error",
			error: { status: 500 },
			onRetry: fn(),
		},
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("We could not load this feedback")).toBeVisible();
		// With no record to name it, the level is named for what it is.
		await expect(screen.getByRole("dialog")).toHaveAccessibleName("Feedback");
		await expect(panel.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

export const HiddenSource: Story = {
	args: {
		feedback: ready({
			...partialPackageFeedbackDetail,
			textHidden: true,
			body: null,
			bodyPreview: null,
		}),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("heading", { level: 2, name: titleOf(partialPackageFeedbackDetail) });
		panel.getByText("Feedback text is hidden because access to its sources could not be verified.");
		await expect(panel.queryByText(/No feedback text is recorded/u)).toBeNull();
	},
};

const [basisSource] = reviewFeedbackDetail.basis;
if (basisSource === undefined) {
	throw new Error("The feedback fixtures must rest on a result");
}
const RESULT_ID = basisSource.resultId;
const SUMMARY = resultSummary(basisSource.result);
const PRACTICE = basisSource.result.practiceName;
const OTHER_RESULT_ID = "cccccccc-0000-4000-8000-000000000001";
const UNAVAILABLE_RESULT_ID = "cccccccc-0000-4000-8000-000000000002";
const PROBLEM_ID = "dddddddd-0000-4000-8000-000000000001";
const SECOND_PROBLEM_ID = "dddddddd-0000-4000-8000-000000000002";
const OTHER_PROBLEM_ID = "dddddddd-0000-4000-8000-000000000003";

/** The feedback, resting on each basis in the order given. */
function restingOn(basis: Omit<FeedbackBasis, "ordinal">[]): ReviewFeedbackDetail {
	return { ...reviewFeedbackDetail, basis: basis.map((entry, ordinal) => ({ ...entry, ordinal })) };
}

function basisEntries(panel: HTMLElement): HTMLElement[] {
	return within(within(panel).getByRole("list", { name: "Based on" })).getAllByRole("listitem");
}

/**
 * Feedback that rests on a whole result and on one problem of the same result names that result
 * once, with both bases under it. The technical facts still list each basis.
 */
export const BasedOnAResultAndOneOfItsProblems: Story = {
	args: {
		feedback: ready(
			restingOn([
				{ resultId: RESULT_ID, problemId: null, result: basisSource.result },
				{ resultId: RESULT_ID, problemId: PROBLEM_ID, result: basisSource.result },
			]),
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const [entry, ...others] = basisEntries(panelElement);
		if (entry === undefined) {
			throw new Error("The feedback names no result it rests on");
		}
		await expect(others).toHaveLength(0);
		const result = within(entry);
		await expect(result.getAllByRole("link", { name: SUMMARY })).toHaveLength(1);
		await expect(entry).toHaveTextContent(
			`${PRACTICE} · The whole observation · One problem of this observation`,
		);
		await expect(levelsOpenedBy(result.getByRole("link", { name: SUMMARY }))).toEqual([
			`result:${RESULT_ID}`,
		]);
		await expect(
			levelsOpenedBy(
				result.getByRole("link", { name: `One problem of this observation: ${SUMMARY}` }),
			),
		).toEqual([`problem:${PROBLEM_ID}`]);
		const panel = within(panelElement);
		await expect(panel.getByRole("button", { name: "Copy basis observation 1" })).toBeVisible();
		await expect(panel.getByRole("button", { name: "Copy basis observation 2" })).toBeVisible();
		await expect(panel.getByRole("button", { name: "Copy basis problem 2" })).toBeVisible();
		await expect(panel.getByText(PROBLEM_ID)).toBeVisible();
	},
};

/**
 * Each result once, in the order the feedback first names it: several problems of one result each
 * open their own, another result with the same summary keeps its own entry, and a result whose text
 * is unavailable still opens.
 */
export const BasedOnSeveralResults: Story = {
	args: {
		feedback: ready(
			restingOn([
				{ resultId: RESULT_ID, problemId: PROBLEM_ID, result: basisSource.result },
				{
					resultId: OTHER_RESULT_ID,
					problemId: OTHER_PROBLEM_ID,
					result: { ...basisSource.result, id: OTHER_RESULT_ID },
				},
				{ resultId: RESULT_ID, problemId: null, result: basisSource.result },
				{ resultId: RESULT_ID, problemId: SECOND_PROBLEM_ID, result: basisSource.result },
				{
					resultId: UNAVAILABLE_RESULT_ID,
					problemId: null,
					result: { ...basisSource.result, id: UNAVAILABLE_RESULT_ID, summary: null },
				},
			]),
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const [first, second, third, ...others] = basisEntries(panelElement);
		if (first === undefined || second === undefined || third === undefined) {
			throw new Error("The feedback names fewer than three results");
		}
		await expect(others).toHaveLength(0);

		// Two results with the same summary are still two entries, each opening its own result.
		await expect(
			within(panelElement)
				.getAllByRole("link", { name: SUMMARY })
				.map((link) => levelsOpenedBy(link)),
		).toEqual([[`result:${RESULT_ID}`], [`result:${OTHER_RESULT_ID}`]]);

		const result = within(first);
		await expect(result.getAllByRole("link", { name: SUMMARY })).toHaveLength(1);
		await expect(first).toHaveTextContent(
			`${PRACTICE} · The whole observation · One problem of this observation · Another problem of this observation`,
		);
		for (const [name, problemId] of [
			[`One problem of this observation: ${SUMMARY} (reference 1 of 2)`, PROBLEM_ID],
			[`Another problem of this observation: ${SUMMARY} (reference 2 of 2)`, SECOND_PROBLEM_ID],
		] as const) {
			await expect(levelsOpenedBy(result.getByRole("link", { name }))).toEqual([
				`problem:${problemId}`,
			]);
		}

		const other = within(second);
		await expect(other.getAllByRole("link", { name: SUMMARY })).toHaveLength(1);
		await expect(second).toHaveTextContent(`${PRACTICE} · One problem of this observation`);
		await expect(second).not.toHaveTextContent("The whole observation");
		await expect(second).not.toHaveTextContent("Another problem of this observation");
		await expect(
			levelsOpenedBy(
				other.getByRole("link", { name: `One problem of this observation: ${SUMMARY}` }),
			),
		).toEqual([`problem:${OTHER_PROBLEM_ID}`]);

		const unavailable = within(third);
		await expect(third).toHaveTextContent(`${PRACTICE} · The whole observation`);
		await expect(unavailable.getAllByRole("link")).toHaveLength(1);
		await expect(
			levelsOpenedBy(unavailable.getByRole("link", { name: "Observation text unavailable" })),
		).toEqual([`result:${UNAVAILABLE_RESULT_ID}`]);
		await expectNoPanelOverflow(panelElement);
	},
};
