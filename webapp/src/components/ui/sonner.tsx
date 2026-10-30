import {
	CircleCheckIcon,
	InfoIcon,
	Loader2Icon,
	OctagonXIcon,
	TriangleAlertIcon,
} from "lucide-react";
import type { MouseEvent } from "react";
import { Toaster as Sonner, type ToasterProps } from "sonner";

import { useTheme } from "@/runtime/theme/ThemeContext";

/**
 * Every toast announces politely, `toast.error` included: sonner exposes no assertive path.
 *
 * ⚠️ Diverges from the shadcn registry: every toast has a close button, because a failure stays until
 * the reader closes it (`@/lib/toast`). And a keyboard press on a toast's button that leaves other toasts
 * open moves focus to their list (`keepFocusAmongToasts`). Re-vendoring drops both.
 */
function Toaster({ ...props }: ToasterProps) {
	const { theme } = useTheme();

	return (
		<div className="contents" onClickCapture={keepFocusAmongToasts}>
			<Sonner
				theme={theme}
				closeButton
				toastOptions={{ closeButtonAriaLabel: "Close message" }}
				icons={{
					success: <CircleCheckIcon className="size-4" />,
					info: <InfoIcon className="size-4" />,
					warning: <TriangleAlertIcon className="size-4" />,
					error: <OctagonXIcon className="size-4" />,
					loading: <Loader2Icon className="size-4 animate-spin" />,
				}}
				style={{
					"--normal-bg": "var(--popover)",
					"--normal-text": "var(--popover-foreground)",
					"--normal-border": "var(--border)",
					"--border-radius": "var(--radius)",
				}}
				{...props}
			/>
		</div>
	);
}

/**
 * Sonner removes a closed toast while its button still has focus. The browser then drops focus to the
 * page body, and sonner's own return of focus (to where the reader was before Alt+T) runs only when the
 * focus leaves the list or the last toast closes. So a keyboard press (`detail` 0) that closes one of
 * several toasts moves focus to their list: Tab reaches the next toast, and leaving the list returns
 * the reader to where they were.
 */
function keepFocusAmongToasts(event: MouseEvent<HTMLElement>) {
	const button =
		event.target instanceof Element ? event.target.closest("[data-sonner-toast] button") : null;
	const list = button?.closest("ol");
	if (event.detail === 0 && list && list.querySelectorAll("[data-sonner-toast]").length > 1) {
		list.focus({ preventScroll: true });
	}
}

export { Toaster };
