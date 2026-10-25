import { toast } from "sonner";

/**
 * Each failure gets its own toast id. Sonner removes a dismissed or clicked toast a few frames later
 * by its id, so a reused id lets that late removal take the next failure's toast with it.
 */
let failures = 0;
const idOf = (failure: number) => `heph-unavailable-${failure}`;

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
	toast.dismiss(idOf(failures));
	failures += 1;
	toast.error(again ? "Heph still could not be shown." : "We could not show Heph.", {
		id: idOf(failures),
		duration: Number.POSITIVE_INFINITY,
		dismissible: false,
		action:
			retry === undefined
				? { label: "Reload page", onClick: reloadPage }
				: { label: "Retry", onClick: retry },
	});
}

export function dismissHephUnavailable() {
	toast.dismiss(idOf(failures));
}
