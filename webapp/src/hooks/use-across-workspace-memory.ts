import { useSyncExternalStore } from "react";

/**
 * What Practices across the workspace remembers for its reader, in this browser and nowhere else:
 * whether the workspace is shown. On until the reader turns it off.
 *
 * Storage can be missing or refuse a write (a private window, blocked site data); every read and
 * write is guarded, and the page then simply forgets between visits.
 */
const SHOW_WORKSPACE_KEY = "hephaestus:practices-across-the-workspace:show-workspace";

const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
	listeners.add(listener);
	const onStorage = (event: StorageEvent) => {
		if (event.key === null || event.key === SHOW_WORKSPACE_KEY) {
			listener();
		}
	};
	window.addEventListener("storage", onStorage);
	return () => {
		listeners.delete(listener);
		window.removeEventListener("storage", onStorage);
	};
}

function read(): string | null {
	try {
		return window.localStorage.getItem(SHOW_WORKSPACE_KEY);
	} catch {
		return null;
	}
}

function write(value: string | undefined): void {
	try {
		if (value === undefined) {
			window.localStorage.removeItem(SHOW_WORKSPACE_KEY);
		} else {
			window.localStorage.setItem(SHOW_WORKSPACE_KEY, value);
		}
	} catch {
		// Not remembered; the page still answers for this visit.
	}
	for (const listener of listeners) {
		listener();
	}
}

export interface AcrossWorkspaceMemory {
	showWorkspace: boolean;
	setShowWorkspace: (on: boolean) => void;
}

export function useAcrossWorkspaceMemory(): AcrossWorkspaceMemory {
	const stored = useSyncExternalStore(subscribe, read, () => null);
	return {
		showWorkspace: stored !== "off",
		setShowWorkspace: (on) => write(on ? undefined : "off"),
	};
}
