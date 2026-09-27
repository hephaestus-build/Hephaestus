import { browser } from "@wxt-dev/browser";
import { z } from "zod";

import type { IntentStore } from "~/background/actions";
import {
	EMPTY_SESSION,
	sessionRecordSchema,
	type SessionRecord,
	type SessionStorage,
} from "~/background/session";

/**
 * Two areas, split by what may reach the disk. `storage.local` holds only the instance — no credential,
 * nothing about anybody's work. `storage.session` holds the
 * credentials: it lives in memory, survives the worker stopping and is gone when Chrome closes, and
 * `setAccessLevel` keeps it out of content scripts.
 */
export const instanceSchema = z.object({
	origin: z.string(),
	/** Where the API answers: `<origin>/api` behind the production proxy, the bare origin locally. */
	apiBase: z.string(),
	/** Where the web app answers, for every link out. Never the API server. */
	webAppOrigin: z.string(),
});

export type InstanceConfig = z.infer<typeof instanceSchema>;

const INSTANCE_KEY = "instance";
const SESSION_KEY = "session";
const INTENT_KEY = "pendingAction";

export async function restrictSessionStorage(): Promise<void> {
	await browser.storage.session.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
}

export async function readInstance(): Promise<InstanceConfig | undefined> {
	const stored = await browser.storage.local.get(INSTANCE_KEY);
	const parsed = instanceSchema.safeParse(stored[INSTANCE_KEY]);
	return parsed.success ? parsed.data : undefined;
}

export async function writeInstance(instance: InstanceConfig): Promise<void> {
	await browser.storage.local.set({ [INSTANCE_KEY]: instance });
}

export async function clearLocal(): Promise<void> {
	await browser.storage.local.remove(INSTANCE_KEY);
}

export const chromeSessionStorage: SessionStorage = {
	async read(): Promise<SessionRecord> {
		const stored = await browser.storage.session.get(SESSION_KEY);
		const parsed = sessionRecordSchema.safeParse(stored[SESSION_KEY]);
		return parsed.success ? parsed.data : EMPTY_SESSION;
	},
	async write(record: SessionRecord): Promise<void> {
		await browser.storage.session.set({ [SESSION_KEY]: record });
	},
};

/** The one pending review action a confirmation window may complete; nothing on disk. */
export const chromeIntentStore: IntentStore = {
	async read(): Promise<unknown> {
		const stored = await browser.storage.session.get(INTENT_KEY);
		return stored[INTENT_KEY];
	},
	async write(intent): Promise<void> {
		await (intent === undefined
			? browser.storage.session.remove(INTENT_KEY)
			: browser.storage.session.set({ [INTENT_KEY]: intent }));
	},
};

const reportViewSchema = z.object({
	generation: z.number().int().nonnegative(),
	pageUrl: z.string(),
	expanded: z.boolean(),
	workspaceSlug: z.string().optional(),
});

export type StoredReportView = z.infer<typeof reportViewSchema>;

function reportViewKey(tabId: number): string {
	return `reportView:${tabId}`;
}

/** One tab's report view, only while that tab still shows the work it was chosen for. */
export async function readReportView(tabId: number): Promise<StoredReportView | undefined> {
	const key = reportViewKey(tabId);
	const stored = await browser.storage.session.get(key);
	const parsed = reportViewSchema.safeParse(stored[key]);
	return parsed.success ? parsed.data : undefined;
}

export async function writeReportView(
	tabId: number,
	view: StoredReportView | undefined,
): Promise<void> {
	const key = reportViewKey(tabId);
	await (view === undefined
		? browser.storage.session.remove(key)
		: browser.storage.session.set({ [key]: view }));
}
