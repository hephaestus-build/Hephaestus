import {
	CircleCheckIcon,
	InfoIcon,
	Loader2Icon,
	OctagonXIcon,
	TriangleAlertIcon,
} from "lucide-react";
import { Toaster as Sonner, type ToasterProps } from "sonner";

import { useTheme } from "@/runtime/theme/ThemeContext";

/**
 * Every toast announces politely, `toast.error` included: sonner exposes no assertive path.
 *
 * ⚠️ Diverges from the shadcn registry: every toast has a close button, because a failure stays until
 * the reader closes it (`@/lib/toast`). Re-vendoring drops `closeButton` and its label.
 */
function Toaster({ ...props }: ToasterProps) {
	const { theme } = useTheme();

	return (
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
	);
}

export { Toaster };
