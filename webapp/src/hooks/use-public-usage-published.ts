import { useSyncExternalStore } from "react";

import environment from "@/environment";

/*
 * Whether this server publishes Hephaestus in numbers, for the footer link. The server writes it into the page's
 * config record when the document loads, so the footer sends no request: only `/usage` reads the figures, from the
 * public read quota. It lives as long as the document, outside the query cache, so a global refetch cannot set it
 * back to the value of the first load. A newer answer of the server replaces it.
 */
let published = environment.publicUsage;
const listeners = new Set<() => void>();

/** Records a newer answer of the server: a confirmed switch change, a policy read, or what `/usage` found. */
export function setPublicUsagePublished(next: boolean): void {
	if (next === published) {
		return;
	}
	published = next;
	for (const listener of listeners) {
		listener();
	}
}

function subscribe(listener: () => void): () => void {
	listeners.add(listener);
	return () => {
		listeners.delete(listener);
	};
}

function snapshot(): boolean {
	return published;
}

export function usePublicUsagePublished(): boolean {
	return useSyncExternalStore(subscribe, snapshot);
}
