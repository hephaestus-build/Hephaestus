import { useSyncExternalStore } from "react";

import {
	type Estimate,
	ESTIMATE_STANDINGS,
} from "@/components/practices-across-the-workspace/across-workspace-copy";

/**
 * What Practices across the workspace remembers for its reader, in this browser and nowhere else:
 * the two switches, and each estimate keyed by workspace and practice group. Estimates are never
 * sent to the server, so no workspace figure and no other reader can ever see one.
 *
 * Storage can be missing or refuse a write (a private window, blocked site data); every read and
 * write is guarded, and the page then simply forgets between visits.
 */
const PREFIX = "hephaestus:practices-across-the-workspace";

const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
	listeners.add(listener);
	const onStorage = (event: StorageEvent) => {
		if (event.key === null || event.key.startsWith(PREFIX)) {
			listener();
		}
	};
	window.addEventListener("storage", onStorage);
	return () => {
		listeners.delete(listener);
		window.removeEventListener("storage", onStorage);
	};
}

function read(key: string): string | null {
	try {
		return window.localStorage.getItem(key);
	} catch {
		return null;
	}
}

function write(key: string, value: string | undefined): void {
	try {
		if (value === undefined) {
			window.localStorage.removeItem(key);
		} else {
			window.localStorage.setItem(key, value);
		}
	} catch {
		// Not remembered; the page still answers for this visit.
	}
	for (const listener of listeners) {
		listener();
	}
}

const switchKey = (name: "show-workspace" | "ask-first") => `${PREFIX}:${name}`;
const estimateKey = (workspaceSlug: string, groupSlug: string) =>
	`${PREFIX}:estimate:${workspaceSlug}:${groupSlug}`;

const ESTIMATES: ReadonlySet<string> = new Set([...ESTIMATE_STANDINGS, "SKIPPED"]);
const isEstimate = (value: string | null): value is Estimate =>
	value !== null && ESTIMATES.has(value);

/** A switch that is on until the reader turns it off. */
function useRememberedSwitch(
	name: "show-workspace" | "ask-first",
): [boolean, (on: boolean) => void] {
	const stored = useSyncExternalStore(
		subscribe,
		() => read(switchKey(name)),
		() => null,
	);
	return [stored !== "off", (on) => write(switchKey(name), on ? undefined : "off")];
}

export interface AcrossWorkspaceMemory {
	showWorkspace: boolean;
	setShowWorkspace: (on: boolean) => void;
	askFirst: boolean;
	setAskFirst: (on: boolean) => void;
	estimates: Record<string, Estimate | undefined>;
	setEstimate: (groupSlug: string, estimate: Estimate) => void;
	/** Answers the given groups with Skipped. */
	skip: (groupSlugs: readonly string[]) => void;
	/** Forgets the answers for the given groups. */
	forget: (groupSlugs: readonly string[]) => void;
}

export function useAcrossWorkspaceMemory(
	workspaceSlug: string,
	groupSlugs: readonly string[],
): AcrossWorkspaceMemory {
	const [showWorkspace, setShowWorkspace] = useRememberedSwitch("show-workspace");
	const [askFirst, setAskFirst] = useRememberedSwitch("ask-first");
	// One string for every group's answer, so the snapshot is stable between renders.
	const joined = useSyncExternalStore(
		subscribe,
		() =>
			groupSlugs.map((groupSlug) => read(estimateKey(workspaceSlug, groupSlug)) ?? "").join("\n"),
		() => "",
	);
	const stored = joined.split("\n");
	const estimates: Record<string, Estimate | undefined> = {};
	for (const [index, groupSlug] of groupSlugs.entries()) {
		const value = stored[index] ?? null;
		estimates[groupSlug] = isEstimate(value) ? value : undefined;
	}
	return {
		showWorkspace,
		setShowWorkspace,
		askFirst,
		setAskFirst,
		estimates,
		setEstimate: (groupSlug, estimate) => write(estimateKey(workspaceSlug, groupSlug), estimate),
		skip: (slugs) => {
			for (const groupSlug of slugs) {
				write(estimateKey(workspaceSlug, groupSlug), "SKIPPED");
			}
		},
		forget: (slugs) => {
			for (const groupSlug of slugs) {
				write(estimateKey(workspaceSlug, groupSlug), undefined);
			}
		},
	};
}
