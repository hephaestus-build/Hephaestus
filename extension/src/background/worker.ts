import { browser } from "@wxt-dev/browser";

import { DEV_PROVIDER_TYPE, isSignInProvider } from "@/lib/sign-in-providers";
import {
	type ActionDependencies,
	bindConfirmation,
	confirmAction,
	confirmationClosed,
	discardAction,
	forgetAction,
	openAction,
	previewAction,
} from "~/background/actions";
import { AuthenticatedApi, publicApi } from "~/background/api";
import {
	type ContextEnvironment,
	observationPage,
	resolveContext,
	workFeedback,
	WorkspaceDirectory,
} from "~/background/context";
import { toRpcError, WorkerError } from "~/background/errors";
import { discoverInstance } from "~/background/instance";
import {
	attestPanel,
	configurePanel,
	type MentorDependencies,
	mentorPanel,
	mentorThread,
	newConversation,
	openPanel,
	prepareTurn,
	relayTurn,
} from "~/background/mentor";
import { type Credentials, SessionStore } from "~/background/session";
import { signIn, type SignInMethod } from "~/background/sign-in";
import {
	grantedPatterns,
	reconcileProviderScript,
	siteAccessEntries,
} from "~/background/site-access";
import {
	chromeIntentStore,
	chromeSessionStorage,
	clearLocal,
	type InstanceConfig,
	readInstance,
	readMentorBinding,
	readReportView,
	writeInstance,
	writeMentorBinding,
	writeReportView,
} from "~/background/storage";
import { WORK_PARAMETER } from "~/shared/frame-messages";
import { originPattern } from "~/shared/instance-url";
import { MENTOR_TURN_PORT, panelTabOf, type TurnEvent, turnRequestSchema } from "~/shared/mentor";
import type { ReviewContext, WorkSubject } from "~/shared/review-context";
import {
	type AccountSummary,
	type AppState,
	type InstanceSummary,
	requestSchema,
	type RpcEvent,
	type RpcRequest,
	type RpcResponses,
	type RpcResult,
	type SessionSummary,
} from "~/shared/rpc";
import { authorize, classifySender, type SenderFacts } from "~/shared/sender-policy";
import { parseWorkPage } from "~/shared/work-url";

export const DEVELOPMENT_BUILD = import.meta.env.MODE !== "production";

function broadcast(event: RpcEvent): void {
	const send = async () => {
		try {
			await browser.runtime.sendMessage(event);
		} catch {
			// Rejects when no view is open to hear it, which is the ordinary case.
		}
	};
	void send();
}

const directory = new WorkspaceDirectory();

/** The one mentor turn each tab's Heph panel may have in flight. */
interface ActiveTurn {
	controller: AbortController;
	workUrl?: string;
}
const turns = new Map<number, ActiveTurn>();

function abortTurn(tabId: number): void {
	turns.get(tabId)?.controller.abort();
	turns.delete(tabId);
}

export const session = new SessionStore({
	storage: chromeSessionStorage,
	refresh: async (issuer, refreshToken) => publicApi.refresh(issuer.apiBase, refreshToken),
	revoke: async (issuer, refreshToken) => publicApi.revoke(issuer.apiBase, refreshToken),
	now: () => Date.now(),
	onChange(generation) {
		directory.clear();
		// A reply streaming under the old session stops with it; the server ends the turn.
		for (const tabId of turns.keys()) {
			abortTurn(tabId);
		}
		broadcast({ type: "state-changed", generation });
	},
});

// Only local state transitions run here. OAuth, discovery, and issuer revocation never hold it.
let configurationLock: Promise<undefined> = Promise.resolve(undefined);
let configurationRequest = 0;

async function withConfiguration<T>(task: () => Promise<T>): Promise<T> {
	const previous = configurationLock;
	const released = Promise.withResolvers<undefined>();
	configurationLock = released.promise;
	try {
		await previous;
		return await task();
	} finally {
		released.resolve(undefined);
	}
}

// Preference writes are local and serialized so a late old write cannot replace a newer choice.
let reportViewLock: Promise<undefined> = Promise.resolve(undefined);

async function withReportViews<T>(task: () => Promise<T>): Promise<T> {
	const previous = reportViewLock;
	const released = Promise.withResolvers<undefined>();
	reportViewLock = released.promise;
	try {
		await previous;
		return await task();
	} finally {
		released.resolve(undefined);
	}
}

function revokeDetached(credentials: Credentials | undefined): void {
	if (credentials !== undefined) {
		void publicApi.revoke(credentials.issuer.apiBase, credentials.tokens.refreshToken);
	}
}

async function startSignIn(method: SignInMethod): Promise<AppState> {
	// Capturing both facts under the publication lock prevents an old instance being paired with
	// the epoch of a newer configuration. Adoption's epoch CAS handles completion in either order.
	const { instance, epoch } = await withConfiguration(async () => {
		const selected = await requireInstance();
		const snapshot = await session.snapshot();
		return { instance: selected, epoch: snapshot.epoch };
	});
	const credentials = await signIn(instance, method);
	if (!(await session.adopt(credentials, epoch))) {
		throw new WorkerError("stale", "Something changed while you were signing in. Try again.");
	}
	return appState();
}

let sessionSummary: { generation: number; summary: Promise<SessionSummary> } | undefined;

function accountOf(user: Awaited<ReturnType<AuthenticatedApi["currentUser"]>>): AccountSummary {
	return {
		displayName: user.displayName ?? user.username ?? "",
		username: user.username ?? "",
		avatarUrl: user.avatarUrl,
		instanceAdmin: user.appRole === "APP_ADMIN",
	};
}

async function loadSessionSummary(
	api: AuthenticatedApi,
	sessionExpiresAt: string,
): Promise<SessionSummary> {
	try {
		const [user, consent] = await Promise.all([api.currentUser(), api.consent()]);
		const account = accountOf(user);
		return consent.completed
			? { status: "signed-in", account, sessionExpiresAt }
			: { status: "consent-required", account };
	} catch (error) {
		if (error instanceof WorkerError && error.code === "consent-required") {
			return { status: "consent-required" };
		}
		if (error instanceof WorkerError && error.code === "signed-out") {
			return { status: "signed-out" };
		}
		throw error;
	}
}

function instanceSummaryOf(instance: InstanceConfig): InstanceSummary {
	return {
		origin: instance.origin,
		host: new URL(instance.origin).host,
		webAppOrigin: instance.webAppOrigin,
	};
}

async function appState(): Promise<AppState> {
	const { snapshot, instance } = await withConfiguration(async () => ({
		snapshot: await session.snapshot(),
		instance: await readInstance(),
	}));
	const base = { generation: snapshot.generation, developmentBuild: DEVELOPMENT_BUILD };
	const api = AuthenticatedApi.fromSnapshot(snapshot, session);
	if (api === undefined || snapshot.credentials === undefined) {
		return instance === undefined
			? { ...base, session: { status: "signed-out" } }
			: { ...base, instance: instanceSummaryOf(instance), session: { status: "signed-out" } };
	}
	// Signed in: the instance shown is the one that issued the credentials, from the same snapshot.
	const instanceSummary = instanceSummaryOf(snapshot.credentials.issuer);
	if (sessionSummary?.generation !== snapshot.generation) {
		sessionSummary = {
			generation: snapshot.generation,
			summary: loadSessionSummary(api, snapshot.credentials.tokens.sessionExpiresAt),
		};
	}
	const cached = sessionSummary;
	try {
		const summary = await cached.summary;
		return { ...base, instance: instanceSummary, session: summary };
	} finally {
		// Share only in-flight reads. Consent, account roles and server revocation can change without
		// a local event; each later visit must also pass SessionStore's absolute-deadline check.
		if (sessionSummary === cached) {
			sessionSummary = undefined;
		}
	}
}

async function requireInstance(): Promise<InstanceConfig> {
	const instance = await readInstance();
	if (instance === undefined) {
		throw new WorkerError(
			"not-configured",
			"Connect the extension to a Hephaestus instance first.",
		);
	}
	return instance;
}

/**
 * Everything an authenticated command needs, from one session snapshot: the instance is the issuer
 * of the stored credentials, so an instance read and a credential read can never be paired from two
 * different moments.
 */
async function environment(): Promise<ContextEnvironment<AuthenticatedApi>> {
	const snapshot = await session.snapshot();
	const api = AuthenticatedApi.fromSnapshot(snapshot, session);
	if (api === undefined || snapshot.credentials === undefined) {
		throw new WorkerError("signed-out", "Sign in to Hephaestus to see this work.");
	}
	return {
		instance: snapshot.credentials.issuer,
		api,
		async tabUrl(tabId) {
			let url: string | undefined;
			try {
				const tab = await browser.tabs.get(tabId);
				({ url } = tab);
			} catch {
				// A closed tab shows nothing.
			}
			return url;
		},
		directory,
		now: () => new Date(),
	};
}

const actions: ActionDependencies = {
	store: chromeIntentStore,
	now: () => Date.now(),
	// The window's handle to the one pending intent: 122 random bits.
	newId: () => crypto.randomUUID(),
};

/**
 * The confirmation window: a small top-level extension window that no page can frame or cover. It
 * opens on the extension's blank page, is bound to the intent by the tab id Chrome returns, and only
 * then is sent to the address that names the intent, so no other tab can ever hold a usable copy. If
 * any step fails, this intent — and no newer one — is forgotten.
 */
async function openConfirmation(intentId: string): Promise<void> {
	const page = browser.runtime.getURL("/action.html");
	let tabId: number | undefined;
	try {
		const created = await browser.windows.create({
			url: page,
			type: "popup",
			width: 480,
			height: 640,
			focused: true,
		});
		tabId = created?.tabs?.[0]?.id;
		if (tabId === undefined || !(await bindConfirmation(actions, intentId, tabId))) {
			throw new WorkerError("stale", "The confirmation window could not be opened. Try again.");
		}
		await browser.tabs.update(tabId, { url: `${page}#${intentId}` });
	} catch (error) {
		await forgetAction(actions, intentId);
		if (tabId !== undefined) {
			await browser.tabs.remove(tabId).catch(() => undefined);
		}
		throw error instanceof WorkerError
			? error
			: new WorkerError("stale", "The confirmation window could not be opened. Try again.");
	}
}

/**
 * A closed tab takes its report view and its Heph panel's conversation binding with it; a closed
 * confirmation window, its pending intent.
 */
export async function onTabRemoved(tabId: number): Promise<void> {
	abortTurn(tabId);
	await Promise.all([
		confirmationClosed(actions, tabId),
		withReportViews(async () => writeReportView(tabId, undefined)),
		writeMentorBinding(tabId, undefined),
	]);
}

/** Configure eligible granted tabs before a press, and end a turn when its work is left. */
export async function onTabUpdated(tabId: number, change: { url?: string }): Promise<void> {
	if (change.url === undefined) {
		return;
	}
	const page = parseWorkPage(change.url);
	const turn = turns.get(tabId);
	if (turn !== undefined && (turn.workUrl === undefined || turn.workUrl !== page?.canonicalUrl)) {
		abortTurn(tabId);
	}
	await configureTabPanel(tabId, change.url);
}

async function configureTabPanel(tabId: number, url: string | undefined): Promise<void> {
	try {
		if (parseWorkPage(url) === undefined) {
			await browser.sidePanel.setOptions({ tabId, enabled: false });
		} else {
			await configurePanel(tabId);
		}
	} catch {
		// A closed tab needs no panel.
	}
}

/** The toolbar is the same contextual entry, including pages with no supported inline slot. */
export async function onActionClicked(tab: { id?: number; url?: string }): Promise<void> {
	if (tab.id === undefined || parseWorkPage(tab.url) === undefined) {
		await browser.runtime.openOptionsPage();
		return;
	}
	try {
		// No awaits before Chrome's gesture-bound API.
		await openPanel(tab.id);
	} catch {
		await browser.runtime.openOptionsPage();
	}
}

/** The work a tab shows now, read from Chrome; the key a report view is kept under. */
async function tabWork(tabId: number): Promise<string | undefined> {
	let url: string | undefined;
	try {
		({ url } = await browser.tabs.get(tabId));
	} catch {
		return undefined;
	}
	return parseWorkPage(url)?.canonicalUrl;
}

function requireTab(tabId: number | undefined): number {
	if (tabId === undefined) {
		throw new WorkerError("invalid", "No tab to look at.");
	}
	return tabId;
}

/** `get-context` answers every expected state as a state, so a view has one thing to render. */
async function context(
	tabId: number,
	workspaceSlug?: string,
	subject?: WorkSubject,
): Promise<ReviewContext> {
	const instance = await readInstance();
	if (instance === undefined) {
		return { status: "not-configured" };
	}
	const instanceHost = new URL(instance.origin).host;
	try {
		return await resolveContext(await environment(), tabId, workspaceSlug, subject);
	} catch (error) {
		if (!(error instanceof WorkerError)) {
			throw error;
		}
		switch (error.code) {
			case "signed-out": {
				return { status: "signed-out", instanceHost };
			}
			case "consent-required": {
				return { status: "consent-required", instanceHost, webAppUrl: instance.webAppOrigin };
			}
			case "network":
			case "server": {
				return { status: "error", message: error.message };
			}
			case "cancelled":
			case "conflict":
			case "expired":
			case "forbidden":
			case "invalid":
			case "not-configured":
			case "stale":
			case "unregistered": {
				throw error;
			}
		}
	}
}

/** The workspace the reader chose in this tab's report, while the tab still shows that work. */
async function reportWorkspace(tabId: number): Promise<string | undefined> {
	const [generation, pageUrl, view] = await Promise.all([
		session.generation(),
		tabWork(tabId),
		readReportView(tabId),
	]);
	return view !== undefined && view.generation === generation && view.pageUrl === pageUrl
		? view.workspaceSlug
		: undefined;
}

const mentorDependencies: MentorDependencies = {
	context: async (tabId) => context(tabId, await reportWorkspace(tabId)),
	async environment() {
		const env = await environment();
		return { api: env.api, webAppOrigin: env.instance.webAppOrigin, tabUrl: env.tabUrl };
	},
	readBinding: readMentorBinding,
	writeBinding: writeMentorBinding,
};

/**
 * Starts opening the Heph panel for the tab whose report the reader pressed it in. It runs before the
 * worker awaits anything, because Chrome opens a side panel only while handling that press. Only the
 * report on a work's own page may ask — a list row's preview is about another page's work — and the
 * tab's address, which Chrome reports, must be such a page.
 */
async function startOpeningMentor(sender: SenderFacts): Promise<unknown> {
	const tabId = sender.tab?.id;
	const preview = sender.url === undefined || new URL(sender.url).searchParams.has(WORK_PARAMETER);
	if (tabId === undefined || preview || parseWorkPage(sender.tab?.url) === undefined) {
		return new WorkerError("invalid", "Heph opens beside a pull request, merge request or issue.");
	}
	try {
		await openPanel(tabId);
		return undefined;
	} catch {
		return new WorkerError("stale", "Chrome did not open the Heph panel. Try again.");
	}
}

/** Publishes configuration and invalidates sign-in eligibility as one local transition. */
async function replaceInstance(next: InstanceConfig | undefined, request: number): Promise<void> {
	let detached: Credentials | undefined;
	try {
		await withConfiguration(async () => {
			if (request !== configurationRequest) {
				throw new WorkerError("stale", "The instance selection changed. Try again.");
			}
			const previous = await readInstance();
			detached = await session.detach();
			await clearLocal();
			if (next !== undefined) {
				await writeInstance(next);
			}
			if (previous !== undefined && previous.origin !== next?.origin) {
				try {
					await browser.permissions.remove({ origins: [originPattern(previous.origin)] });
				} catch {
					// The grant may already be gone.
				}
			}
		});
	} finally {
		// Network cleanup never holds the configuration lock or delays publication.
		revokeDetached(detached);
	}
	// A tab can keep its message channel open indefinitely. Updating injected pages must not
	// prevent views from reading the configuration that has already been published.
	await reconcile();
}

type Handler<K extends keyof RpcResponses> = (
	request: Extract<RpcRequest, { type: K }>,
	tabId: number | undefined,
) => Promise<RpcResponses[K]>;

const handlers: { [K in keyof RpcResponses]: Handler<K> } = {
	"get-state": async () => appState(),

	async "configure-instance"(request) {
		configurationRequest += 1;
		const selection = configurationRequest;
		const discovered = await discoverInstance(request, DEVELOPMENT_BUILD);
		await replaceInstance(discovered, selection);
		return appState();
	},

	async "clear-instance"() {
		configurationRequest += 1;
		await replaceInstance(undefined, configurationRequest);
		return appState();
	},

	async "list-sign-in-options"() {
		const instance = await requireInstance();
		const providers = await publicApi.identityProviders(instance.apiBase);
		const registered = await publicApi
			.isRegistered(instance.apiBase, browser.runtime.id)
			.catch(() => false);
		return {
			options: providers.filter(isSignInProvider).map((provider) => ({
				registrationId: provider.registrationId ?? "",
				displayName: provider.displayName ?? provider.registrationId ?? "",
				providerType: provider.providerType ?? "",
			})),
			devSignIn: providers.some((provider) => provider.providerType === DEV_PROVIDER_TYPE),
			registered,
			extensionId: browser.runtime.id,
		};
	},

	"sign-in": async (request) =>
		startSignIn({ kind: "provider", registrationId: request.registrationId }),

	"sign-in-dev": async (request) =>
		startSignIn({ kind: "dev", username: request.username, admin: request.admin }),

	async "sign-out"() {
		revokeDetached(await withConfiguration(async () => session.detach()));
		return appState();
	},

	async "list-site-access"() {
		const env = await environment();
		const sites = await directory.sites(env.api);
		return siteAccessEntries(sites, await grantedPatterns());
	},

	async "get-context"(request, tabId) {
		const tab = requireTab(tabId);
		const answer = await context(tab, request.workspaceSlug, request.subject);
		// A work page the reader can see gets its Heph panel ready, so a later press opens it at once.
		if (answer.status === "ready" && (request.subject?.kind ?? "page") === "page") {
			try {
				await configurePanel(tab);
			} catch {
				// The tab may have closed; its read-only report does not depend on a chat panel.
			}
		}
		return answer;
	},

	async "get-work-feedback"(request, tabId) {
		return workFeedback(
			await environment(),
			requireTab(tabId),
			request.workspaceSlug,
			request.subject,
		);
	},

	async "list-observations"(request, tabId) {
		return observationPage(await environment(), requireTab(tabId), request.workspaceSlug);
	},

	async "get-report-view"(_request, tabId) {
		const tab = requireTab(tabId);
		const generation = await session.generation();
		const [pageUrl, view] = await Promise.all([tabWork(tab), readReportView(tab)]);
		if (generation !== (await session.generation()) || pageUrl !== (await tabWork(tab))) {
			throw new WorkerError("stale", "The report changed while its view was loading.");
		}
		return view !== undefined && view.generation === generation && view.pageUrl === pageUrl
			? { expanded: view.expanded, workspaceSlug: view.workspaceSlug }
			: { expanded: false };
	},

	async "set-report-view"(request, tabId) {
		const tab = requireTab(tabId);
		await withReportViews(async () => {
			const pageUrl = await tabWork(tab);
			if (pageUrl === undefined || request.generation !== (await session.generation())) {
				throw new WorkerError("stale", "The report changed before its view could be saved.");
			}
			await writeReportView(tab, {
				generation: request.generation,
				pageUrl,
				expanded: request.expanded,
				workspaceSlug: request.workspaceSlug,
			});
			// The storage write can overlap sign-out or navigation. Its old generation is never
			// read as current; remove it before another queued preference write is allowed to run.
			if (request.generation !== (await session.generation()) || pageUrl !== (await tabWork(tab))) {
				await writeReportView(tab, undefined);
				throw new WorkerError("stale", "The report changed while its view was being saved.");
			}
		});
		return null;
	},

	async "open-action"(request, tabId) {
		const intent = await openAction(
			await environment(),
			actions,
			requireTab(tabId),
			request.workspaceSlug,
			request.action,
		);
		await openConfirmation(intent);
		return null;
	},

	"get-action": async (request, tabId) =>
		previewAction(await environment(), actions, request.intent, requireTab(tabId)),

	"confirm-action": async (request, tabId) =>
		confirmAction(await environment(), actions, request.intent, requireTab(tabId)),

	async "discard-action"(request, tabId) {
		await discardAction(actions, request.intent, requireTab(tabId));
		return null;
	},

	// Opened by `startOpeningMentor` before dispatch; nothing is left to do here.
	"open-mentor": async () => null,

	async "get-mentor-panel"(_request, tabId) {
		const tab = requireTab(tabId);
		await attestPanel(tab);
		return mentorPanel(mentorDependencies, tab);
	},

	async "get-mentor-thread"(request, tabId) {
		const tab = requireTab(tabId);
		await attestPanel(tab);
		return mentorThread(mentorDependencies, tab, request.threadId);
	},

	async "new-mentor-conversation"(_request, tabId) {
		const tab = requireTab(tabId);
		await attestPanel(tab);
		if (turns.has(tab)) {
			throw new WorkerError("conflict", "Stop Heph's reply before starting a new conversation.");
		}
		await newConversation(mentorDependencies, tab);
		return null;
	},
};

const GENERATION_MOVING = new Set<RpcRequest["type"]>([
	"configure-instance",
	"clear-instance",
	"sign-in",
	"sign-in-dev",
	"sign-out",
]);

async function dispatch<K extends keyof RpcResponses>(
	request: Extract<RpcRequest, { type: K }>,
	tabId: number | undefined,
): Promise<RpcResponses[K]> {
	const handler: Handler<K> = handlers[request.type];
	return handler(request, tabId);
}

/**
 * The only door into the worker. Shape first, then who is asking, then the command — and the reply
 * carries the generation it was computed under.
 */
export async function handleMessage(
	message: unknown,
	sender: SenderFacts,
): Promise<RpcResult<unknown>> {
	const parsed = requestSchema.safeParse(message);
	if (!parsed.success) {
		return {
			ok: false,
			error: { code: "invalid", message: "Unrecognised request." },
			generation: await session.generation(),
		};
	}
	const decision = authorize(parsed.data, sender, browser.runtime.id);
	if (!decision.allowed) {
		return {
			ok: false,
			error: { code: "forbidden", message: "Not allowed from here." },
			generation: await session.generation(),
		};
	}
	// Before the first await: Chrome opens a side panel only while it handles the reader's press.
	const opening = parsed.data.type === "open-mentor" ? startOpeningMentor(sender) : undefined;
	const startedAt = await session.generation();
	// A read is only as current as the generation it began under; a command that moves the
	// generation itself answers with the one it produced.
	const stamp = async () =>
		GENERATION_MOVING.has(parsed.data.type) ? session.generation() : startedAt;
	try {
		const failure = await opening;
		if (failure instanceof WorkerError) {
			throw failure;
		}
		const data = await dispatch(parsed.data, decision.tabId);
		return { ok: true, data, generation: await stamp() };
	} catch (error) {
		return { ok: false, error: toRpcError(error), generation: await stamp() };
	}
}

type Port = ReturnType<typeof browser.runtime.connect>;

/** One turn from an attested panel: checked, fenced, sent, relayed, and always let go of. */
async function runTurn(
	tabId: number,
	message: unknown,
	controller: AbortController,
	post: (event: TurnEvent) => void,
): Promise<void> {
	const request = turnRequestSchema.safeParse(message);
	if (!request.success) {
		post({ type: "failed", message: "That message cannot be sent." });
		return;
	}
	if (turns.has(tabId)) {
		post({ type: "failed", message: "Heph is still answering in this panel." });
		return;
	}
	const active: ActiveTurn = { controller };
	turns.set(tabId, active);
	try {
		await attestPanel(tabId);
		const prepared = await prepareTurn(mentorDependencies, tabId, request.data);
		active.workUrl = prepared.workUrl;
		const turn = await prepared.api.mentorTurn(
			prepared.workspaceSlug,
			prepared.body,
			controller.signal,
		);
		await relayTurn(turn, post, controller.signal);
	} catch (error) {
		if (!controller.signal.aborted) {
			post({ type: "failed", message: toRpcError(error).message });
		}
	} finally {
		if (turns.get(tabId) === active) {
			turns.delete(tabId);
		}
	}
}

/**
 * The door for mentor turns: a port from a Heph panel, which carries one turn's request and its
 * streamed response. The panel closing the port is the reader stopping the reply — the request is
 * aborted, so the server ends the turn — and the worker closes it once the reply has ended.
 */
export function onMentorPort(port: Port): void {
	if (port.name !== MENTOR_TURN_PORT) {
		return;
	}
	const sender: SenderFacts = port.sender ?? {};
	const tabId =
		classifySender(sender, browser.runtime.id) === "mentor" && sender.url !== undefined
			? panelTabOf(new URL(sender.url).search)
			: undefined;
	if (tabId === undefined) {
		port.disconnect();
		return;
	}
	const controller = new AbortController();
	let open = true;
	const post = (event: TurnEvent) => {
		if (!open) {
			return;
		}
		try {
			port.postMessage(event);
		} catch {
			open = false;
			controller.abort();
		}
	};
	port.onDisconnect.addListener(() => {
		open = false;
		controller.abort();
	});
	const finish = async (message: unknown) => {
		port.onMessage.removeListener(start);
		try {
			await runTurn(tabId, message, controller, post);
		} finally {
			if (open) {
				open = false;
				port.disconnect();
			}
		}
	};
	const start = (message: unknown) => {
		void finish(message);
	};
	port.onMessage.addListener(start);
}

export async function reconcile(): Promise<void> {
	await withConfiguration(async () => {
		// The excluded instance and registered matches must describe the same publication. Page
		// queries, notifications and injection are detached by the reconciler and never hold this lock.
		const instance = await readInstance();
		await reconcileProviderScript(instance?.origin);
	});
	const tabs = await browser.tabs.query({});
	await Promise.all(
		tabs.map(async (tab) => {
			if (tab.id !== undefined) {
				await configureTabPanel(tab.id, tab.url);
			}
		}),
	);
}

export async function onSiteAccessRemoved(): Promise<void> {
	await reconcile();
	await session.bump();
	broadcast({ type: "site-access-changed" });
}

export async function onSiteAccessAdded(): Promise<void> {
	await reconcile();
	broadcast({ type: "site-access-changed" });
}
