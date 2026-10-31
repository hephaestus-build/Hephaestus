import type { AnyRouter } from "@tanstack/react-router";

import type { BrowserFaults } from "@/runtime/browser-faults";

const MARKER_KEY = "hephaestus.preload-error-reload-at";
const RELOAD_WINDOW_MS = 60_000;

/**
 * Claims the one reload that a failed chunk load allows. A second failure inside the window means
 * the reload did not help, so the route error shows instead of a reload loop. A browser that
 * refuses storage cannot keep the marker, so it gets no reload.
 */
function claimReload(): boolean {
	try {
		// oxlint-disable-next-line no-restricted-properties -- The reload guard persists wall-clock time outside React.
		const now = Date.now();
		const last = Number(sessionStorage.getItem(MARKER_KEY));
		if (now - last < RELOAD_WINDOW_MS) {
			return false;
		}
		sessionStorage.setItem(MARKER_KEY, String(now));
		return true;
	} catch {
		return false;
	}
}

/**
 * A deploy removes the old hashed chunks, so a tab that still runs the previous build fails to load
 * a chunk. Reloading fetches the new build, as https://vite.dev/guide/build#load-error-handling
 * describes. TanStack Router reloads only for a failed route component; this covers every other
 * dynamic import.
 *
 * A failed preload on hover or focus does nothing. The page may hold input that a reload would
 * discard, and the link's own navigation fails again and reloads. A failed navigation is a fault the
 * reader met, so it reports one, whether the reload follows or the route error shows. Returns the
 * function that removes the handling.
 */
export function installPreloadErrorReload(
	router: AnyRouter,
	faults: Pick<BrowserFaults, "report">,
): () => void {
	const reloadForNavigation = (event: Event) => {
		if (!router.state.isLoading) {
			return;
		}
		// Vite throws the payload when nothing reloads, so the boundary that shows it sees the same error.
		faults.report("CHUNK_LOAD_FAILED", "payload" in event ? event.payload : event, {
			errorType: "CHUNK",
		});
		if (claimReload()) {
			event.preventDefault();
			window.location.reload();
		}
	};
	window.addEventListener("vite:preloadError", reloadForNavigation);
	return () => {
		window.removeEventListener("vite:preloadError", reloadForNavigation);
	};
}
