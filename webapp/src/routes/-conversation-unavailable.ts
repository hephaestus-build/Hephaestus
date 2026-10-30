import { toast } from "@/lib/toast";

/**
 * Each failure gets its own toast id. Sonner removes a dismissed or clicked toast a few frames later
 * by its id, so a reused id lets that late removal take the next failure's toast with it.
 */
let failures = 0;
const idOf = (failure: number) => `conversation-unavailable-${failure}`;

function reloadPage() {
	window.location.reload();
}

/**
 * Says that Hephaestus is missing from the page and how to get it back. It stays until the panel renders
 * again, and the reader cannot close it: closed, nothing would say where Hephaestus went.
 *
 * `retry` is for the first failure of a loaded panel. Without it, as after a retry that failed too or
 * for panel code that did not load, the way back is a page load.
 */
export function showConversationUnavailable({
	retry,
	again,
}: {
	retry?: () => void;
	again: boolean;
}) {
	toast.dismiss(idOf(failures));
	failures += 1;
	toast.error(
		again ? "Ask Hephaestus still could not open." : "We could not open Ask Hephaestus.",
		{
			id: idOf(failures),
			dismissible: false,
			// Sonner draws the close button on a toast that cannot be dismissed, as a button that does nothing.
			closeButton: false,
			action:
				retry === undefined
					? { label: "Reload page", onClick: reloadPage }
					: { label: "Retry", onClick: retry },
		},
	);
}

export function dismissConversationUnavailable() {
	toast.dismiss(idOf(failures));
}
