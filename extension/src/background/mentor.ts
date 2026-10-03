import { browser } from "@wxt-dev/browser";

import { mentorPreferenceReason } from "@/lib/mentor-preference";
import type { ChatThreadDetail, WorkspaceOnboarding } from "~/api/types.gen";
import type { MentorTurn } from "~/background/api";
import { consentRequired, forbidden, server, stale, WorkerError } from "~/background/errors";
import type { MentorBinding } from "~/background/storage";
import {
	MAX_MESSAGE_CHARS,
	type MentorPanel,
	mentorPanelPath,
	pageNoun,
	type TurnEvent,
	type TurnRequest,
	workReference,
} from "~/shared/mentor";
import type { ReviewContext } from "~/shared/review-context";
import { mentorThreadLink, onboardingLink } from "~/shared/web-app-links";
import { parseWorkPage, type WorkPage, workPageLabel } from "~/shared/work-url";

/**
 * The Heph panel's worker half. The panel is Chrome's side panel for one tab, opened only by the
 * reader; the worker configures it, attests every request from it, holds the credential, decides
 * which work a conversation is about from the tab itself, and relays one turn at a time to the
 * mentor's own endpoint. A conversation is ordinary server history: the first message names the work
 * in words the reader sees (`workReference`), and nothing else is added to what they write.
 */

/** Configures one tab's side panel as its Heph panel; the panel's address names the tab. */
export async function configurePanel(tabId: number): Promise<void> {
	await browser.sidePanel.setOptions({ tabId, path: mentorPanelPath(tabId), enabled: true });
}

/**
 * Opens one tab's Heph panel in answer to the reader's press. Chrome opens a side panel only while it
 * is handling that press, so both calls are made at once, before anything is awaited; the
 * configuration is made again here in case the report's resolution has not configured it yet, and
 * Chrome applies the two in order.
 */
export async function openPanel(tabId: number): Promise<unknown> {
	return Promise.all([configurePanel(tabId), browser.sidePanel.open({ tabId })]);
}

function samePanelPath(path: string | undefined, tabId: number): boolean {
	if (path === undefined) {
		return false;
	}
	const expected = browser.runtime.getURL(`/${mentorPanelPath(tabId)}`);
	return browser.runtime.getURL(`/${path.replace(/^\//u, "")}`) === expected || path === expected;
}

/**
 * Proves a request comes from the panel this worker configured for `tabId`: Chrome must still hold
 * exactly that address as the tab's side panel. A panel opened any other way — the page in a tab, a
 * panel left over from another configuration — is refused.
 */
export async function attestPanel(tabId: number): Promise<void> {
	let options: { enabled?: boolean; path?: string };
	try {
		options = await browser.sidePanel.getOptions({ tabId });
	} catch {
		throw forbidden("This panel no longer belongs to a tab. Open Heph from the report again.");
	}
	if (options.enabled !== true || !samePanelPath(options.path, tabId)) {
		throw forbidden("This panel no longer belongs to a tab. Open Heph from the report again.");
	}
}

/** The reads and the one write a panel needs, from one session snapshot. */
export interface MentorApi {
	readonly generation: number;
	assertCurrent: () => Promise<void>;
	onboarding: (workspaceSlug: string) => Promise<WorkspaceOnboarding>;
	thread: (workspaceSlug: string, threadId: string) => Promise<ChatThreadDetail | undefined>;
	mentorTurn: (workspaceSlug: string, turn: object, signal: AbortSignal) => Promise<MentorTurn>;
}

export interface MentorEnvironment {
	api: MentorApi;
	webAppOrigin: string;
	/** The tab's current address, read from Chrome; never from a message. */
	tabUrl: (tabId: number) => Promise<string | undefined>;
}

export interface MentorDependencies {
	/** What the report resolves for the tab's own page, in the workspace the reader chose there. */
	context: (tabId: number) => Promise<ReviewContext>;
	environment: () => Promise<MentorEnvironment>;
	readBinding: (tabId: number) => Promise<MentorBinding | undefined>;
	writeBinding: (tabId: number, binding: MentorBinding | undefined) => Promise<void>;
}

interface ReadyPanel {
	panel: Extract<MentorPanel, { status: "ready" }>;
	env: MentorEnvironment;
	page: WorkPage;
	binding: MentorBinding | undefined;
}

function heldLabel(binding: MentorBinding): string {
	const page = parseWorkPage(binding.workUrl);
	return page === undefined ? "other work" : `${page.repository} ${workPageLabel(page)}`;
}

/** Fails `stale` unless the tab still shows `page` and the session is the one the answer began under. */
async function stillShowing(env: MentorEnvironment, tabId: number, page: WorkPage): Promise<void> {
	if (parseWorkPage(await env.tabUrl(tabId))?.canonicalUrl !== page.canonicalUrl) {
		throw stale();
	}
	await env.api.assertCurrent();
}

async function resolvePanel(
	deps: MentorDependencies,
	tabId: number,
): Promise<MentorPanel | ReadyPanel> {
	const context = await deps.context(tabId);
	switch (context.status) {
		case "ready": {
			break;
		}
		case "not-configured":
		case "unsupported-page": {
			return { status: context.status };
		}
		case "signed-out": {
			return { status: "signed-out", instanceHost: context.instanceHost };
		}
		case "consent-required": {
			return { status: "consent-required", webAppUrl: context.webAppUrl };
		}
		case "no-workspace": {
			return { status: "no-workspace", siteOrigin: context.siteOrigin };
		}
		case "not-found":
		case "choose-workspace": {
			return { status: context.status, workLabel: context.workLabel };
		}
		case "error": {
			return { status: "error", message: context.message };
		}
	}
	const page = parseWorkPage(context.pageUrl);
	if (page === undefined) {
		return { status: "unsupported-page" };
	}
	const env = await deps.environment();
	const { slug } = context.workspace;
	const [onboarding, stored] = await Promise.all([
		env.api.onboarding(slug),
		deps.readBinding(tabId),
	]);
	await stillShowing(env, tabId, page);
	const binding =
		stored !== undefined &&
		stored.generation === env.api.generation &&
		stored.workspaceSlug === slug
			? stored
			: undefined;
	const holds = binding?.workUrl === page.canonicalUrl ? binding : undefined;
	return {
		env,
		page,
		binding,
		panel: {
			status: "ready",
			workspace: context.workspace,
			work: {
				noun: pageNoun(page),
				label: workPageLabel(page),
				repository: page.repository,
				title: context.work.title,
				canonicalUrl: page.canonicalUrl,
			},
			reference: workReference(page),
			notice: mentorPreferenceReason(onboarding),
			onboardingUrl: onboardingLink(env.webAppOrigin, slug),
			threadId: holds?.threadId,
			threadUrl:
				holds === undefined ? undefined : mentorThreadLink(env.webAppOrigin, slug, holds.threadId),
			heldAbout: binding !== undefined && holds === undefined ? heldLabel(binding) : undefined,
		},
	};
}

function isReady(value: MentorPanel | ReadyPanel): value is ReadyPanel {
	return "panel" in value;
}

/** What the panel shows for its tab now. Nothing is sent to Heph by asking. */
export async function mentorPanel(deps: MentorDependencies, tabId: number): Promise<MentorPanel> {
	const resolved = await resolvePanel(deps, tabId);
	return isReady(resolved) ? resolved.panel : resolved;
}

/**
 * The stored transcript of the conversation the tab holds, for a panel that was closed and opened
 * again. Only that conversation, and only while the tab still shows its work.
 */
export async function mentorThread(
	deps: MentorDependencies,
	tabId: number,
	threadId: string,
): Promise<{ messages: unknown[] }> {
	const resolved = await resolvePanel(deps, tabId);
	if (!isReady(resolved) || resolved.panel.threadId !== threadId) {
		throw stale();
	}
	const { env, page, panel } = resolved;
	const detail = await env.api.thread(panel.workspace.slug, threadId);
	await stillShowing(env, tabId, page);
	// A conversation whose first turn never reached the server has nothing stored yet.
	return { messages: detail?.messages ?? [] };
}

/** Lets go of the tab's conversation; it stays on the server and in the web app. */
export async function newConversation(deps: MentorDependencies, tabId: number): Promise<void> {
	await deps.writeBinding(tabId, undefined);
}

function textOf(request: TurnRequest): string {
	return request.body.message.parts
		.flatMap((part) => (part.type === "text" && typeof part.text === "string" ? [part.text] : []))
		.join("");
}

/** A turn ready to send: where, as whom, and the body narrowed to what the server reads. */
export interface PreparedTurn {
	api: MentorApi;
	workspaceSlug: string;
	workUrl: string;
	body: object;
}

/**
 * Decides whether a turn may be sent, from the tab and the stored binding alone. A conversation is
 * continued only while the tab shows its work. A new one must open with the reference to the work
 * the tab shows now, so the first message the reader saw is the one that is stored; the tab is bound
 * to it before anything is sent.
 */
export async function prepareTurn(
	deps: MentorDependencies,
	tabId: number,
	request: TurnRequest,
): Promise<PreparedTurn> {
	const resolved = await resolvePanel(deps, tabId);
	if (!isReady(resolved)) {
		if (resolved.status === "consent-required") {
			throw consentRequired();
		}
		throw stale();
	}
	const { env, page, panel } = resolved;
	if (panel.heldAbout !== undefined) {
		throw new WorkerError(
			"stale",
			`This conversation is about ${panel.heldAbout}. Go back to it, or start a new one here.`,
		);
	}
	if (panel.notice !== undefined) {
		throw forbidden("Heph is not ready for your AI choice here. Check the notice in the panel.");
	}
	const text = textOf(request);
	if (text.trim() === "" || text.length > MAX_MESSAGE_CHARS) {
		throw new WorkerError("invalid", "That message cannot be sent.");
	}
	const { id, message, trigger, messageId } = request.body;
	const opens = panel.threadId !== id;
	if (opens) {
		const opening = `${panel.reference}\n\n`;
		if (!text.startsWith(opening) || text.slice(opening.length).trim() === "") {
			throw new WorkerError(
				"stale",
				"The work in this tab changed. Your message was not sent; start again here.",
			);
		}
	}
	await stillShowing(env, tabId, page);
	if (opens) {
		await deps.writeBinding(tabId, {
			generation: env.api.generation,
			workspaceSlug: panel.workspace.slug,
			workUrl: page.canonicalUrl,
			threadId: id,
		});
	}
	return {
		api: env.api,
		workspaceSlug: panel.workspace.slug,
		workUrl: page.canonicalUrl,
		body: {
			id,
			message: { id: message.id, role: "user", parts: [{ type: "text", text }] },
			trigger,
			...(messageId === undefined ? {} : { messageId }),
		},
	};
}

/** The server's reason for refusing a turn, in words the panel can show as they are. */
function refusal(status: number, error: unknown): string {
	if (status === 428) {
		return consentRequired().message;
	}
	if (status === 403 || status === 404) {
		return "Hephaestus did not let your account talk with Heph in this workspace.";
	}
	if (status === 429) {
		return "You have sent a lot of messages in a short time. Wait a moment, then try again.";
	}
	if (
		typeof error === "object" &&
		error !== null &&
		"detail" in error &&
		typeof error.detail === "string" &&
		error.detail.trim() !== ""
	) {
		return error.detail.slice(0, 500);
	}
	return server(status).message;
}

/**
 * Relays one turn's response to the panel: its status, then its bytes as text exactly as they arrive
 * — including the server's keep-alive comments, so a long quiet stretch still reaches the panel and
 * keeps this worker running — and then its end. A refusal is relayed as its reason, which the AI SDK
 * raises as the turn's error.
 */
export async function relayTurn(
	turn: MentorTurn,
	post: (event: TurnEvent) => void,
	signal: AbortSignal,
): Promise<void> {
	if (turn.stream === null) {
		post({ type: "response", status: turn.status, contentType: "text/plain" });
		post({ type: "chunk", text: refusal(turn.status, turn.error) });
		post({ type: "end" });
		return;
	}
	post({ type: "response", status: turn.status, contentType: turn.contentType });
	const reader = turn.stream.getReader();
	const decoder = new TextDecoder();
	try {
		for (;;) {
			const { done, value } = await reader.read();
			if (done) {
				break;
			}
			post({ type: "chunk", text: decoder.decode(value, { stream: true }) });
		}
		const rest = decoder.decode();
		if (rest !== "") {
			post({ type: "chunk", text: rest });
		}
		post({ type: "end" });
	} catch {
		if (!signal.aborted) {
			post({
				type: "failed",
				message: "The connection to Hephaestus was lost, so the reply stopped.",
			});
		}
	} finally {
		reader.releaseLock();
	}
}
