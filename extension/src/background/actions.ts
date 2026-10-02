import { z } from "zod";

import type { ReviewRequestOutcome } from "~/api/types.gen";
import {
	type ContextApi,
	type ContextEnvironment,
	requireReady,
	sameWork,
} from "~/background/context";
import { forbidden, WorkerError } from "~/background/errors";
import {
	type ActionOutcome,
	type ActionPreview,
	actionIntentSchema,
	type ReviewAction,
	reviewActionSchema,
} from "~/shared/review-actions";
import type { ReadyContext } from "~/shared/review-context";

/**
 * Review changes started from a provider page. The page's frame can be covered or moved by the page
 * to misdirect a real click, so it never changes anything: it asks the worker to open the
 * extension's confirmation window, and the worker records what that window may do as one pending
 * intent. The intent is bound to the session generation, the provider tab and the exact work it was
 * opened from, and the confirmation tab the worker created; the
 * window reads it back through the worker, which checks all of that again, and once more in the one
 * local step that consumes it before the one request is sent.
 */
export interface ActionApi extends ContextApi {
	requestReview: (slug: string, kind: string, id: number) => Promise<ReviewRequestOutcome>;
}

/** Long enough to read the window, short enough that a forgotten one does not linger. */
export const INTENT_LIFETIME_MS = 10 * 60_000;

const intentSchema = z.object({
	id: actionIntentSchema,
	generation: z.number().int(),
	/** The provider tab the report asked from. */
	tabId: z.number().int(),
	pageUrl: z.string(),
	workspaceSlug: z.string(),
	work: z.object({ kind: z.string(), id: z.string() }),
	action: reviewActionSchema,
	expiresAt: z.number(),
	/**
	 * The confirmation window's tab, as Chrome returned it when the worker created it. Bound before
	 * that tab is sent to the actionable address, so no other copy of the page can use the intent.
	 */
	confirmationTabId: z.number().int().optional(),
});

export type ActionIntent = z.infer<typeof intentSchema>;

/** Where the one pending intent lives: `storage.session`, so it outlives an idle worker. */
export interface IntentStore {
	read: () => Promise<unknown>;
	write: (intent: ActionIntent | undefined) => Promise<void>;
}

export interface ActionDependencies {
	store: IntentStore;
	now: () => number;
	newId: () => string;
}

/**
 * Every read-compare-write of the stored intent runs here, one at a time, and nothing else does: no
 * network call and no user interaction is ever awaited inside. Chrome runs one worker at a time, and
 * a restarted worker loses only this queue, never the stored state it protects.
 */
let critical: Promise<undefined> = Promise.resolve(undefined);

async function exclusive<T>(task: () => Promise<T>): Promise<T> {
	const previous = critical;
	const released = Promise.withResolvers<undefined>();
	critical = released.promise;
	try {
		await previous;
		return await task();
	} finally {
		released.resolve(undefined);
	}
}

async function storedIntent(deps: ActionDependencies): Promise<ActionIntent | undefined> {
	const parsed = intentSchema.safeParse(await deps.store.read());
	return parsed.success ? parsed.data : undefined;
}

/**
 * Applies `change` to the stored intent only if it is still `intentId`: a reader that started before a
 * replacement can never touch the newer intent.
 */
async function compareAndSet(
	deps: ActionDependencies,
	intentId: string,
	change: (intent: ActionIntent) => ActionIntent | undefined | false,
): Promise<boolean> {
	return exclusive(async () => {
		const intent = await storedIntent(deps);
		if (intent?.id !== intentId) {
			return false;
		}
		const next = change(intent);
		if (next === false) {
			return false;
		}
		await deps.store.write(next);
		return true;
	});
}

/** Removes the intent if it is still this one; a newer intent survives. */
export async function forgetAction(deps: ActionDependencies, intentId: string): Promise<void> {
	await compareAndSet(deps, intentId, () => undefined);
}

function expired(): WorkerError {
	return new WorkerError(
		"expired",
		"This confirmation is no longer valid. Start again from the practice review on the page.",
	);
}

/** Whether the reader may ask for a review of this work now, by the server's current answer. */
function allowed(context: ReadyContext): void {
	if (!context.canRequestReview) {
		throw forbidden("Your account cannot ask for a review of this work.");
	}
}

/**
 * Records the one pending intent for the tab's work, replacing any earlier one. It is not usable
 * until {@link bindConfirmation} names the window that may complete it.
 */
export async function openAction(
	env: ContextEnvironment<ActionApi>,
	deps: ActionDependencies,
	tabId: number,
	workspaceSlug: string,
	action: ReviewAction,
): Promise<string> {
	const { context, stillShowing } = await requireReady(env, tabId, workspaceSlug);
	allowed(context);
	await stillShowing();
	const intent: ActionIntent = {
		id: deps.newId(),
		generation: env.api.generation,
		tabId,
		pageUrl: context.pageUrl,
		workspaceSlug,
		work: { kind: context.work.kind, id: context.work.id },
		action,
		expiresAt: deps.now() + INTENT_LIFETIME_MS,
	};
	await exclusive(async () => deps.store.write(intent));
	return intent.id;
}

/** Binds the intent to the one tab the worker created for it; only an unbound intent can be bound. */
export async function bindConfirmation(
	deps: ActionDependencies,
	intentId: string,
	confirmationTabId: number,
): Promise<boolean> {
	return compareAndSet(deps, intentId, (intent) =>
		intent.confirmationTabId === undefined ? { ...intent, confirmationTabId } : false,
	);
}

/** A closed confirmation window takes its intent with it, and no other. */
export async function confirmationClosed(deps: ActionDependencies, tabId: number): Promise<void> {
	await exclusive(async () => {
		const intent = await storedIntent(deps);
		if (intent?.confirmationTabId === tabId) {
			await deps.store.write(undefined);
		}
	});
}

/**
 * The intent, if it is the one this window was bound to and still within its lifetime. An expired one
 * is removed — if it is still the stored one.
 */
async function pending(
	deps: ActionDependencies,
	intentId: string,
	senderTabId: number,
): Promise<ActionIntent> {
	const intent = await storedIntent(deps);
	if (intent?.id !== intentId || intent.confirmationTabId !== senderTabId) {
		throw expired();
	}
	if (deps.now() > intent.expiresAt) {
		await forgetAction(deps, intentId);
		throw expired();
	}
	return intent;
}

/**
 * The intent, checked against everything it was bound to: the confirmation window, the session
 * generation, the source tab still open on the same page, the work resolving to the same record in
 * the same workspace, and the reader still allowed. Anything changed discards it.
 */
async function revalidate(
	env: ContextEnvironment<ActionApi>,
	deps: ActionDependencies,
	intentId: string,
	senderTabId: number,
) {
	const intent = await pending(deps, intentId, senderTabId);
	try {
		if (env.api.generation !== intent.generation) {
			throw expired();
		}
		const { context, stillShowing } = await requireReady(env, intent.tabId, intent.workspaceSlug);
		if (context.pageUrl !== intent.pageUrl || !sameWork(intent.work, context)) {
			throw expired();
		}
		allowed(context);
		return { intent, context, stillShowing };
	} catch (error) {
		if (
			error instanceof WorkerError &&
			(error.code === "expired" || error.code === "stale" || error.code === "forbidden")
		) {
			await forgetAction(deps, intentId);
		}
		throw error;
	}
}

export async function previewAction(
	env: ContextEnvironment<ActionApi>,
	deps: ActionDependencies,
	intentId: string,
	senderTabId: number,
): Promise<ActionPreview> {
	const { intent, context, stillShowing } = await revalidate(env, deps, intentId, senderTabId);
	await stillShowing();
	return {
		action: intent.action,
		instanceHost: context.instanceHost,
		workspace: context.workspace,
		work: context.work,
	};
}

/**
 * Sends the one request. After the remote checks, the intent is consumed in one local step that
 * compares everything it was bound to again; only the caller that consumed it sends anything, and the
 * removal is stored before the request leaves. A second click, a reload, a lost reply or a worker
 * restart therefore never sends it twice: the reader starts over from the page, which shows what
 * actually happened first.
 */
export async function confirmAction(
	env: ContextEnvironment<ActionApi>,
	deps: ActionDependencies,
	intentId: string,
	senderTabId: number,
): Promise<ActionOutcome> {
	const { context, stillShowing } = await revalidate(env, deps, intentId, senderTabId);
	await stillShowing();
	const consumed = await compareAndSet(deps, intentId, (current) =>
		current.confirmationTabId === senderTabId &&
		current.generation === env.api.generation &&
		deps.now() <= current.expiresAt
			? undefined
			: false,
	);
	if (!consumed) {
		throw expired();
	}
	// Storage can yield while the source tab navigates. The consumed intent stays consumed if
	// this final fence fails; no request has left and no later click may replay it.
	try {
		await stillShowing();
	} catch (error) {
		if (error instanceof WorkerError && error.code === "stale") {
			throw expired();
		}
		throw error;
	}
	try {
		return await send(env, context);
	} catch (error) {
		// Only the server's own refusal says nothing happened. Anything else after the request left —
		// no answer, or a session that changed while it was out — leaves the outcome unknown.
		if (error instanceof WorkerError && (error.code === "forbidden" || error.code === "conflict")) {
			throw error;
		}
		throw new WorkerError(
			"network",
			"Hephaestus did not confirm the answer, so the change may or may not have gone through.",
		);
	}
}

async function send(
	env: ContextEnvironment<ActionApi>,
	context: ReadyContext,
): Promise<ActionOutcome> {
	const outcome = await env.api.requestReview(
		context.workspace.slug,
		context.work.kind,
		Number(context.work.id),
	);
	return {
		kind: "request-review",
		status: outcome.status,
		reasonDescription: outcome.status === "REFUSED" ? outcome.reasonDescription : undefined,
	};
}

/** The window's own "Cancel": removes its intent, and only while it is still this window's. */
export async function discardAction(
	deps: ActionDependencies,
	intentId: string,
	senderTabId: number,
): Promise<void> {
	await compareAndSet(deps, intentId, (intent) =>
		intent.confirmationTabId === senderTabId ? undefined : false,
	);
}
