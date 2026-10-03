import { z } from "zod";

import type { MentorNotice } from "@/lib/mentor-preference";
import { type WorkPage, workPageLabel } from "~/shared/work-url";

/**
 * The Heph panel: Chrome's side panel for one tab, showing one conversation with Heph about the work
 * that tab shows. The panel page is not web-accessible, and only the worker configures which tab it
 * belongs to, by the address it gives the panel (`mentorPanelPath`); no page and no message can.
 */
export const MENTOR_PAGE = "/mentor.html";

/** The address the worker sets as one tab's side panel; the tab id in it is the panel's binding. */
export function mentorPanelPath(tabId: number): string {
	return `mentor.html?tab=${tabId}`;
}

/** The tab a panel address names, or `undefined` for anything that is not exactly such an address. */
export function panelTabOf(search: string): number | undefined {
	const match = /^\?tab=(?<tab>[1-9]\d{0,9})$/u.exec(search);
	const tab = match?.groups?.tab;
	return tab === undefined ? undefined : Number(tab);
}

const NOUNS: Record<WorkPage["kind"], string> = {
	PULL_REQUEST: "pull request",
	MERGE_REQUEST: "merge request",
	ISSUE: "issue",
};

/** How the work names itself on its provider, from its address alone. */
export function pageNoun(page: Pick<WorkPage, "kind">): string {
	return NOUNS[page.kind];
}

/**
 * The line a new conversation's first message starts with, so the reader, Heph and anyone who
 * continues the conversation in the web app all see which work it is about. Built only from the
 * parsed address: no title or description, which other people write, ever speaks in the reader's
 * own message, and nothing from the page itself is read.
 */
export function workReference(page: WorkPage): string {
	return `About ${pageNoun(page)} ${workPageLabel(page)} in ${page.repository}: ${page.canonicalUrl}`;
}

/** The first message of a conversation about `reference`, exactly as it is sent and stored. */
export function firstMessage(reference: string, text: string): string {
	return `${reference}\n\n${text}`;
}

/** The work the panel's tab shows, as the panel names it. */
export interface MentorWork {
	/** "pull request", "merge request" or "issue". */
	noun: string;
	/** "#12" or "!4". */
	label: string;
	repository: string;
	/** The work's title as Hephaestus recorded it, for the header only; it is never sent. */
	title?: string;
	canonicalUrl: string;
}

/** What the panel shows; every expected state is a state, so the view has one thing to render. */
export type MentorPanel =
	| { status: "not-configured" }
	| { status: "signed-out"; instanceHost: string }
	| { status: "consent-required"; webAppUrl: string }
	| { status: "unsupported-page" }
	| { status: "no-workspace"; siteOrigin: string }
	/** The tab shows work no workspace of the reader's follows, or none it may see; the server does not say which. */
	| { status: "not-found"; workLabel: string }
	| { status: "choose-workspace"; workLabel: string }
	| { status: "error"; message: string }
	| {
			status: "ready";
			workspace: { slug: string; displayName: string };
			work: MentorWork;
			/** The line the first message starts with (`workReference`). */
			reference: string;
			/** Why Heph will not answer this reader here, if it will not; nothing may be sent then. */
			notice?: MentorNotice;
			/** Where the reader changes their AI choice in the web app. */
			onboardingUrl: string;
			/** The conversation this tab holds about this work, if one was started. */
			threadId?: string;
			/** Where that conversation continues in the web app. */
			threadUrl?: string;
			/** The tab moved on: the conversation it holds is about this other work. */
			heldAbout?: string;
	  };

/** The port one mentor turn streams over, from the panel to the worker and back. */
export const MENTOR_TURN_PORT = "mentor-turn";

const uuid = z.uuid();

/**
 * The one request a turn port carries: the AI SDK's own body, narrowed to what the server reads. The
 * worker chooses the address, the workspace, the headers and the credential; the panel cannot.
 */
export const turnRequestSchema = z.strictObject({
	type: z.literal("start"),
	body: z.object({
		id: uuid,
		message: z.object({
			id: uuid,
			role: z.literal("user"),
			parts: z
				.array(z.looseObject({ type: z.string() }))
				.max(16)
				.refine((parts) => parts.some((part) => part.type === "text"), "A message needs text"),
		}),
		trigger: z.enum(["submit-message", "regenerate-message"]),
		messageId: uuid.optional(),
	}),
});

export type TurnRequest = z.infer<typeof turnRequestSchema>;

/** What the worker sends back on a turn port: the response's status, its bytes as text, its end. */
export const turnEventSchema = z.discriminatedUnion("type", [
	z.strictObject({
		type: z.literal("response"),
		status: z.number().int().min(200).max(599),
		contentType: z.string().max(200),
	}),
	z.strictObject({ type: z.literal("chunk"), text: z.string() }),
	z.strictObject({ type: z.literal("end") }),
	z.strictObject({ type: z.literal("failed"), message: z.string().max(500) }),
]);

export type TurnEvent = z.infer<typeof turnEventSchema>;

/** The longest message the panel sends; the server applies its own, possibly lower, limit. */
export const MAX_MESSAGE_CHARS = 16_000;
