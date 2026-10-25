import { toast } from "sonner";

/** One toast for every Heph failure, so a repeat updates it rather than stacking a second. */
const HEPH_UNAVAILABLE = "heph-unavailable";

function reloadPage() {
	window.location.reload();
}

/**
 * Says that Heph is missing from the page and how to get it back. It stays until the panel renders
 * again, and the reader cannot close it: closed, nothing would say where Heph went.
 *
 * `retry` is for the first failure of a loaded panel. Without it, as after a retry that failed too or
 * for panel code that did not load, the way back is a page load.
 */
export function showHephUnavailable({ retry, again }: { retry?: () => void; again: boolean }) {
	toast.error(again ? "Heph still could not be shown." : "We could not show Heph.", {
		id: HEPH_UNAVAILABLE,
		duration: Number.POSITIVE_INFINITY,
		dismissible: false,
		action:
			retry === undefined
				? { label: "Reload page", onClick: reloadPage }
				: { label: "Retry", onClick: retry },
	});
}

export function dismissHephUnavailable() {
	toast.dismiss(HEPH_UNAVAILABLE);
}
