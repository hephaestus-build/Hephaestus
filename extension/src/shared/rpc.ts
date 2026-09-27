import { z } from "zod";

import {
	type ActionOutcome,
	type ActionPreview,
	actionIntentSchema,
	reviewActionSchema,
} from "~/shared/review-actions";
import type { ObservationPage, ReviewContext, WorkFeedback } from "~/shared/review-context";

/**
 * Every message a view may send the worker, as one closed union. There is no generic request: no
 * field names a URL, a method or a header, and the work a command acts on is always re-derived by the
 * worker from the tab, never taken from the message. The only addresses a message carries are the
 * instance (and, locally, web app) origins the user typed in the options page, which the worker
 * validates again.
 */
// The server's own slug rule (`Workspace.workspaceSlug`).
const slug = z.string().regex(/^[a-z0-9][a-z0-9-]{2,50}$/u);

/**
 * Which work a read is about: the tab's own page, or one row of the list the tab shows. The row's
 * address is an untrusted selector; the worker accepts it only against the tab's actual list.
 */
const subject = z
	.discriminatedUnion("kind", [
		z.strictObject({ kind: z.literal("page") }),
		z.strictObject({
			kind: z.literal("list-row"),
			url: z.url({ protocol: /^https?$/u }).max(2048),
		}),
	])
	.optional();

export const requestSchema = z.discriminatedUnion("type", [
	z.object({ type: z.literal("get-state") }),
	z.object({
		type: z.literal("configure-instance"),
		origin: z.string().max(2048),
		// Only a development build asks for it: a local API server does not serve the web app.
		webAppOrigin: z.string().max(2048).optional(),
	}),
	z.object({ type: z.literal("clear-instance") }),
	z.object({ type: z.literal("list-sign-in-options") }),
	z.object({
		type: z.literal("sign-in"),
		registrationId: z.string().regex(/^[A-Za-z0-9_.-]{1,64}$/u),
	}),
	z.object({
		type: z.literal("sign-in-dev"),
		username: z.string().regex(/^[A-Za-z0-9_.-]{1,39}$/u),
		admin: z.boolean(),
	}),
	z.object({ type: z.literal("sign-out") }),
	z.object({ type: z.literal("list-site-access") }),
	z.strictObject({
		type: z.literal("get-context"),
		workspaceSlug: slug.optional(),
		subject,
	}),
	z.strictObject({ type: z.literal("get-work-feedback"), workspaceSlug: slug, subject }),
	// The reader's own observations are the work page's: a list row's preview cannot name them.
	z.strictObject({ type: z.literal("list-observations"), workspaceSlug: slug }),
	z.strictObject({ type: z.literal("get-report-view") }),
	z.strictObject({
		type: z.literal("set-report-view"),
		/** The generation the frame rendered under; the worker drops a write from an older one. */
		generation: z.number().int().min(0),
		expanded: z.boolean(),
		workspaceSlug: slug.optional(),
	}),
	z.strictObject({
		type: z.literal("open-action"),
		workspaceSlug: slug,
		action: reviewActionSchema,
	}),
	z.strictObject({ type: z.literal("get-action"), intent: actionIntentSchema }),
	z.strictObject({ type: z.literal("confirm-action"), intent: actionIntentSchema }),
	z.strictObject({ type: z.literal("discard-action"), intent: actionIntentSchema }),
]);

export type RpcRequest = z.infer<typeof requestSchema>;
export type RpcCommand = RpcRequest["type"];

/** Who is asking, decided by the worker from `MessageSender` alone (`sender-policy.ts`). */
export type Surface = "options" | "inline" | "action";

/**
 * Which surface may send which command. The inline frame sits inside a page the provider controls,
 * which can overlay it to redirect a real click, so it may only read about its own tab, and ask for
 * the confirmation window. Only that window, a top-level extension page no site can frame, confirms.
 */
export const COMMAND_SURFACES: Record<RpcCommand, readonly Surface[]> = {
	"get-state": ["options", "inline", "action"],
	"configure-instance": ["options"],
	"clear-instance": ["options"],
	"list-sign-in-options": ["options"],
	"sign-in": ["options"],
	"sign-in-dev": ["options"],
	"sign-out": ["options"],
	"list-site-access": ["options"],
	"get-context": ["inline"],
	"get-work-feedback": ["inline"],
	"list-observations": ["inline"],
	"get-report-view": ["inline"],
	"set-report-view": ["inline"],
	"open-action": ["inline"],
	"get-action": ["action"],
	"confirm-action": ["action"],
	"discard-action": ["action"],
};

export type RpcErrorCode =
	| "forbidden"
	| "invalid"
	| "stale"
	| "conflict"
	| "expired"
	| "signed-out"
	| "consent-required"
	| "not-configured"
	| "unregistered"
	| "cancelled"
	| "network"
	| "server";

export interface RpcError {
	code: RpcErrorCode;
	message: string;
}

/**
 * `generation` is the worker's at the moment it answered; a view that has since heard of a newer one
 * drops the answer instead of rendering it.
 */
export type RpcResult<T> =
	| { ok: true; data: T; generation: number }
	| { ok: false; error: RpcError; generation: number };

export interface InstanceSummary {
	origin: string;
	host: string;
	webAppOrigin: string;
}

export interface AccountSummary {
	displayName: string;
	username: string;
	avatarUrl?: string;
	instanceAdmin: boolean;
}

export type SessionSummary =
	| { status: "signed-out" }
	| { status: "consent-required"; account?: AccountSummary }
	| { status: "signed-in"; account: AccountSummary; sessionExpiresAt: string };

export interface AppState {
	generation: number;
	instance?: InstanceSummary;
	session: SessionSummary;
	developmentBuild: boolean;
}

export interface SignInOption {
	registrationId: string;
	displayName: string;
	providerType: string;
}

export interface SignInOptions {
	options: SignInOption[];
	devSignIn: boolean;
	/** `false` when the instance has not registered this extension's id. */
	registered: boolean;
	extensionId: string;
}

export interface SiteAccessEntry {
	origin: string;
	providerType: "GITHUB" | "GITLAB";
	workspaces: string[];
	granted: boolean;
}

/**
 * What the reader chose in one tab's report — opened or not, and which workspace — kept by the worker
 * for the work that tab shows, so a provider re-render or a switch between the conversation and the
 * diff keeps it. Only the frame sets it; the page cannot.
 */
export interface ReportViewState {
	expanded: boolean;
	workspaceSlug?: string;
}

export interface RpcResponses {
	"get-state": AppState;
	"configure-instance": AppState;
	"clear-instance": AppState;
	"list-sign-in-options": SignInOptions;
	"sign-in": AppState;
	"sign-in-dev": AppState;
	"sign-out": AppState;
	"list-site-access": SiteAccessEntry[];
	"get-context": ReviewContext;
	"get-work-feedback": WorkFeedback;
	"list-observations": ObservationPage;
	"get-report-view": ReportViewState;
	"set-report-view": null;
	"open-action": null;
	"get-action": ActionPreview;
	"confirm-action": ActionOutcome;
	"discard-action": null;
}

/** Worker → views. Carries no data: a view that hears one asks again. */
export const eventSchema = z.discriminatedUnion("type", [
	z.object({ type: z.literal("state-changed"), generation: z.number().int() }),
	z.object({ type: z.literal("site-access-changed") }),
]);

export type RpcEvent = z.infer<typeof eventSchema>;
