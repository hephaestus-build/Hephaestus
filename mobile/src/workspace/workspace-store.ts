import * as SecureStore from "expo-secure-store";
import { useSyncExternalStore } from "react";

/**
 * The workspace the tabs show, remembered so reopening the app returns to it. Every read and write is
 * tagged with the session it belongs to, and one from an earlier session is dropped: a slow read that
 * finishes after an account switch cannot choose the next account's workspace.
 */
const KEY = "hephaestus.workspace.v1";

let selection: { session: string; slug: string | null | undefined } = {
	session: "",
	slug: undefined,
};
const listeners = new Set<() => void>();

function publish(session: string, slug: string | null): void {
	if (selection.session !== session) {
		return;
	}
	selection = { session, slug };
	for (const listener of listeners) {
		listener();
	}
}

/** `undefined` while it is being read, `null` when none is chosen yet. */
export function useSelectedWorkspace(): string | null | undefined {
	return useSyncExternalStore(
		(listener) => {
			listeners.add(listener);
			return () => {
				listeners.delete(listener);
			};
		},
		() => selection.slug,
	);
}

/** A new session starts with no choice until its own is read. */
export function resetWorkspace(session: string, signedOut: boolean): void {
	selection = { session, slug: undefined };
	for (const listener of listeners) {
		listener();
	}
	if (signedOut) {
		void SecureStore.deleteItemAsync(KEY);
	}
}

export async function loadSelectedWorkspace(session: string, apiBaseUrl: string): Promise<void> {
	const raw = await SecureStore.getItemAsync(KEY);
	const [base, slug] = raw === null ? [] : raw.split("\n");
	publish(session, base === apiBaseUrl && slug !== undefined && slug !== "" ? slug : null);
}

export async function selectWorkspace(
	session: string,
	apiBaseUrl: string,
	slug: string,
): Promise<void> {
	publish(session, slug);
	if (selection.session === session) {
		await SecureStore.setItemAsync(KEY, `${apiBaseUrl}\n${slug}`);
	}
}
