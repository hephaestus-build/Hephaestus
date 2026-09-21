import type * as React from "react";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add textarea` drops the following; re-apply it.
 *
 * `variant="bare"` strips the field chrome for a textarea inside a frame that draws its own edge and
 * focus ring, and keeps body-size type at every width.
 */
function Textarea({
	className,
	variant = "default",
	...props
}: React.ComponentProps<"textarea"> & { variant?: "default" | "bare" }) {
	return (
		<textarea
			data-slot="textarea"
			data-variant={variant}
			className={cn(
				"flex field-sizing-content min-h-16 w-full rounded-lg border border-input bg-transparent px-2.5 py-2 text-base transition-colors outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30 dark:disabled:bg-input/80 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40",
				variant === "bare" &&
					"border-0 bg-transparent p-0 shadow-none outline-none focus-visible:ring-0 md:text-base",
				className,
			)}
			{...props}
		/>
	);
}

export { Textarea };
