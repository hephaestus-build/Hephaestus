import { useSyncExternalStore } from "react";

const getServerSnapshot = () => false;

/**
 * Read the browser value on the initial render and subscribe to changes to this query.
 * Waiting for an effect would paint a desktop sidebar once on a phone.
 */
export function useMediaQuery(query: string): boolean {
	return useSyncExternalStore(
		(onStoreChange) => {
			const media = window.matchMedia(query);
			media.addEventListener("change", onStoreChange);
			return () => media.removeEventListener("change", onStoreChange);
		},
		() => window.matchMedia(query).matches,
		getServerSnapshot,
	);
}
