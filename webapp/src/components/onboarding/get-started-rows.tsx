import type { ReactElement } from "react";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { combinePanelStates, type LoadState } from "@/components/common/panel-state";
import { definitionFor } from "@/components/common/status-def";
import type { FirstRunContent } from "@/components/practice-profile/ProfileFirstRun";
import { MEMBER_AI_CHOICE_DEFS } from "@/components/practice-vocabulary/data-handling-defs";
import type { TourChapterId } from "@/components/tour/tour-chapters";
import { conversationNotice } from "@/lib/conversation-availability";
import { requiredLinks } from "@/lib/onboarding-links";

import { type GetStartedReview, reviewStepStatus } from "./get-started-open";
import type { GetStartedLearnRow, GetStartedSetupRow, GetStartedState } from "./GetStarted";

/** The reader's facts that Get started is built from. Each comes from the server, except `finished`. */
export interface GetStartedFacts {
	workspaceName: string;
	onboarding: WorkspaceOnboarding;
	/**
	 * Where practices are on: the overview's facts about the first review, and what the first-run
	 * state says about them. Absent where the workspace reviews no practices.
	 */
	review?: { facts: GetStartedReview; content: FirstRunContent | undefined };
	/** Hephaestus can answer this member here: the same gate as the Ask Hephaestus button. */
	conversation: boolean;
	/** The tour chapters this account finished in this browser. */
	finished: readonly string[];
}

/** The controls the rows lead to. Each is a `render` slot, so the words stay with the row. */
export interface GetStartedLinks {
	/** The page where the reader makes their AI choice and connects accounts. */
	setup: ReactElement;
	/** Starts a tour chapter in place; absent where the page has no tour. */
	startChapter?: (chapter: TourChapterId) => ReactElement;
	conversation: ReactElement;
}

const LEARN_CHAPTERS: readonly { chapter: TourChapterId; title: string; summary: string }[] = [
	{
		chapter: "how-it-works",
		title: "See how your work becomes feedback",
		summary: "A short tour of this page, from your work to a feedback card.",
	},
	{
		chapter: "your-profile",
		title: "Learn to read a feedback card",
		summary: "A short tour of the parts of a card and what you can do with it.",
	},
];

/**
 * The rows of Get started, in the order of the path to a first review. A row shows only where it
 * applies, and a setup row is done only when the server says that its fact is true.
 * `get-started-open` owns which steps show and how each one stands.
 */
export function getStartedRows(
	facts: GetStartedFacts,
	links: GetStartedLinks,
): { setup: GetStartedSetupRow[]; learn: GetStartedLearnRow[] } {
	const setup = [
		...aiChoiceRow(facts, links.setup),
		...accountRows(facts, links.setup),
		...reviewRow(facts),
	];
	const { startChapter } = links;
	const chapters =
		facts.review === undefined || startChapter === undefined
			? []
			: LEARN_CHAPTERS.map(({ chapter, title, summary }) => ({
					id: chapter,
					title,
					summary,
					render: startChapter(chapter),
					finished: facts.finished.includes(chapter),
				}));
	const conversation: GetStartedLearnRow[] = facts.conversation
		? [
				{
					id: "conversation",
					title: "Ask Hephaestus a question",
					summary: "Ask about your feedback, the practices, or your recent work.",
					render: links.conversation,
					finished: false,
				},
			]
		: [];
	return { setup, learn: [...chapters, ...conversation] };
}

const AI_CHOICE_TITLE = "Choose how AI handles your work";

function aiChoiceRow(
	{ onboarding }: GetStartedFacts,
	setupLink: ReactElement,
): GetStartedSetupRow[] {
	if (!onboarding.aiChoiceRequired) {
		return [];
	}
	const { aiChoice } = onboarding;
	return [
		aiChoice === undefined
			? {
					id: "ai-choice",
					title: AI_CHOICE_TITLE,
					status: "to-do",
					summary: "Hephaestus reviews your work and answers you only with the AI that you allow.",
					action: <InlineLink render={setupLink}>Choose your AI</InlineLink>,
				}
			: {
					id: "ai-choice",
					title: AI_CHOICE_TITLE,
					status: "done",
					summary: `You chose ${definitionFor(MEMBER_AI_CHOICE_DEFS, aiChoice).label}.`,
				},
	];
}

function accountRows(
	{ onboarding, workspaceName }: GetStartedFacts,
	setupLink: ReactElement,
): GetStartedSetupRow[] {
	return requiredLinks(onboarding.links).map((link) => {
		const id = `account:${link.connectionId}`;
		const title = `Connect your ${link.displayName} account`;
		return link.linked
			? { id, title, status: "done", summary: "Connected." }
			: {
					id,
					title,
					status: "to-do",
					summary: `${workspaceName} uses this account to know which work is yours.`,
					action: <InlineLink render={setupLink}>Connect {link.displayName}</InlineLink>,
				};
	});
}

function reviewRow({ review, onboarding }: GetStartedFacts): GetStartedSetupRow[] {
	if (review === undefined) {
		return [];
	}
	const status = reviewStepStatus(review.facts, onboarding);
	const title = "Your first practice review";
	if (status === "done") {
		return [
			{
				id: "first-review",
				title,
				status,
				summary: "Hephaestus has reviewed your work. The result is beside the page title.",
			},
		];
	}
	const { content } = review;
	if (status === undefined || content === undefined) {
		return [];
	}
	return [
		{
			id: "first-review",
			title,
			status,
			summary: (
				<>
					<span className="block font-medium text-foreground">{content.title}</span>
					{content.description}
				</>
			),
			notes: content.notes,
			// A step that waits for one above it has no way on of its own: the step above has it.
			action: status === "cannot-start" ? undefined : content.action,
		},
	];
}

export interface GetStartedInputs {
	workspaceName: string;
	/** The member onboarding read, which the workspace layout already holds, and its state. */
	onboarding: { state: LoadState; data: WorkspaceOnboarding | undefined };
	/**
	 * Where practices are on: the overview's state and facts, and what the first-run state says
	 * (`firstRunContent`), which the page that shows the first-run state already holds.
	 */
	review?: {
		state: LoadState;
		facts: GetStartedReview | undefined;
		content: FirstRunContent | undefined;
	};
	finished: readonly string[];
	links: GetStartedLinks;
}

/**
 * Get started from reads the page already makes: the member onboarding read and the overview. It
 * never reads the feedback cards, because reading them delivers them. A failed read shows nothing:
 * the list is optional guidance, and an alert for it would greet every member, though most of them
 * never see the list.
 */
export function getStartedState({
	onboarding,
	review,
	links,
	...facts
}: GetStartedInputs): GetStartedState {
	const state = combinePanelStates([onboarding.state, review?.state ?? { status: "ready" }]);
	if (state.status !== "ready" || onboarding.data === undefined) {
		return { status: "loading" };
	}
	const reviewFacts = review?.facts;
	return {
		status: "ready",
		...getStartedRows(
			{
				...facts,
				onboarding: onboarding.data,
				review:
					review === undefined || reviewFacts === undefined
						? undefined
						: { facts: reviewFacts, content: review.content },
				// The same gate as the Ask Hephaestus button: a row only where Hephaestus can answer.
				conversation: conversationNotice(onboarding.data) === undefined,
			},
			links,
		),
	};
}
