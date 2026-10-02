import { browser } from "@wxt-dev/browser";

import type { WorkspaceSite } from "~/background/context";
import { providerAccessSchema } from "~/content/site-access-message";
import { originPattern, patternOrigin } from "~/shared/instance-url";
import type { SiteAccessEntry } from "~/shared/rpc";

export const PROVIDER_SCRIPT_ID = "hephaestus-provider";
export const PROVIDER_SCRIPT_FILE = "content-scripts/provider.js";

/**
 * The pages the provider script should run on: every concrete origin the user granted, except the
 * instance itself — its own web app needs no practice review report. A wildcard grant (which the
 * extension never asks for) is not treated as consent to run everywhere.
 */
export function desiredMatches(granted: readonly string[], instanceOrigin?: string): string[] {
	const instancePattern = instanceOrigin === undefined ? undefined : originPattern(instanceOrigin);
	const concrete = granted.filter(
		(pattern) => patternOrigin(pattern) !== undefined && pattern !== instancePattern,
	);
	return [...new Set(concrete)].sort();
}

export async function grantedPatterns(): Promise<string[]> {
	const permissions = await browser.permissions.getAll();
	return permissions.origins ?? [];
}

/**
 * Makes the registered provider script match exactly the granted origins. Runs at startup and
 * whenever a grant is added or removed; losing a grant unregisters before anything else can run.
 */
let reconciliation: Promise<void> = Promise.resolve();
interface PageAccess {
	matches: readonly string[];
	pendingInjection: Set<string>;
}
let pageAccess: PageAccess = { matches: [], pendingInjection: new Set() };

/** Page acknowledgements are not part of committing a permission change. */
function updatePages(matches: string[], added: string[]): void {
	const next: PageAccess = {
		matches,
		// A superseded tab query must not consume the first injection owed to a newly granted site.
		pendingInjection: new Set(
			[...pageAccess.pendingInjection, ...added].filter((pattern) => matches.includes(pattern)),
		),
	};
	pageAccess = next;
	void updateOpenPages(next);
}

export async function reconcileProviderScript(instanceOrigin?: string): Promise<string[]> {
	const previous = reconciliation;
	const done = Promise.withResolvers<undefined>();
	reconciliation = done.promise;
	try {
		// Permission events may overlap startup. Read current grants only when this turn begins.
		await previous;
		return await reconcile(instanceOrigin);
	} finally {
		done.resolve(undefined);
	}
}

async function reconcile(instanceOrigin?: string): Promise<string[]> {
	const matches = desiredMatches(await grantedPatterns(), instanceOrigin);
	const registered = await browser.scripting.getRegisteredContentScripts({
		ids: [PROVIDER_SCRIPT_ID],
	});
	if (matches.length === 0) {
		if (registered.length > 0) {
			await browser.scripting.unregisterContentScripts({ ids: [PROVIDER_SCRIPT_ID] });
		}
		updatePages(matches, []);
		return matches;
	}
	const script = {
		id: PROVIDER_SCRIPT_ID,
		js: [PROVIDER_SCRIPT_FILE],
		matches,
		runAt: "document_idle" as const,
		allFrames: false,
		persistAcrossSessions: true,
		world: "ISOLATED" as const,
	};
	await (registered.length > 0
		? browser.scripting.updateContentScripts([script])
		: browser.scripting.registerContentScripts([script]));
	const previous = new Set(registered.flatMap((entry) => entry.matches ?? []));
	updatePages(
		matches,
		matches.filter((pattern) => !previous.has(pattern)),
	);
	return matches;
}

/**
 * Registering a script affects future documents only. Inject newly allowed sites into their current
 * top-level documents too; WXT invalidates any older copy if navigation raced the registration.
 * Revocation leaves injected scripts alive, so explicitly tell each existing copy to tear down.
 * An ungranted tab has no readable URL; it must receive the disabled state, never retain a panel.
 */
async function updateOpenPages(access: PageAccess): Promise<void> {
	try {
		const tabs = await browser.tabs.query({});
		if (access !== pageAccess) {
			return;
		}
		for (const tab of tabs) {
			if (tab.id === undefined) {
				continue;
			}
			const pattern =
				tab.url !== undefined && (tab.url.startsWith("https:") || tab.url.startsWith("http:"))
					? originPattern(new URL(tab.url).origin)
					: undefined;
			const enabled = pattern !== undefined && access.matches.includes(pattern);
			// tabs.sendMessage resolves with the recipient's response, not with delivery. Frozen pages
			// may never respond; neither another page nor a subsequent revocation may wait for that.
			void notifyPage(tab.id, enabled);
			if (enabled && access.pendingInjection.has(pattern)) {
				void injectPage(tab.id, pattern);
			}
		}
		access.pendingInjection.clear();
	} catch {
		// Browser shutdown can interrupt the tab inventory. Keep pending origins for the next pass.
	}
}

async function notifyPage(tabId: number, enabled: boolean, documentId?: string): Promise<void> {
	try {
		const message = providerAccessSchema.parse({ type: "hephaestus:provider-access", enabled });
		if (documentId === undefined) {
			await browser.tabs.sendMessage(tabId, message);
		} else {
			await browser.tabs.sendMessage(tabId, message, { documentId });
		}
	} catch {
		// Most tabs have no provider script; a targeted document may already have navigated away.
	}
}

async function injectPage(tabId: number, pattern: string): Promise<void> {
	try {
		const results = await browser.scripting.executeScript({
			target: { tabId, frameIds: [0] },
			files: [PROVIDER_SCRIPT_FILE],
			world: "ISOLATED",
		});
		// Chrome checks the host grant itself. If an already-dispatched injection nevertheless finishes
		// after exclusion/revocation, remove that exact document's UI, never a replacement document.
		await reconciliation;
		if (!pageAccess.matches.includes(pattern)) {
			for (const result of results) {
				void notifyPage(tabId, false, result.documentId);
			}
		}
	} catch {
		// The tab closed, navigated or lost its grant while the injection was in flight.
	}
}

/** One row per provider site the account's workspaces are connected to. */
export function siteAccessEntries(
	sites: readonly WorkspaceSite[],
	granted: readonly string[],
): SiteAccessEntry[] {
	const bySite = new Map<string, SiteAccessEntry>();
	for (const site of sites) {
		const entry = bySite.get(site.siteOrigin) ?? {
			origin: site.siteOrigin,
			providerType: site.providerType,
			workspaces: [],
			granted: granted.includes(originPattern(site.siteOrigin)),
		};
		entry.workspaces.push(site.displayName);
		bySite.set(site.siteOrigin, entry);
	}
	return [...bySite.values()].sort((a, b) => a.origin.localeCompare(b.origin));
}
