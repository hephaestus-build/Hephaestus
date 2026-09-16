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
				"border-input dark:bg-input/30 focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:aria-invalid:border-destructive/50 disabled:bg-input/50 dark:disabled:bg-input/80 rounded-lg border bg-transparent px-2.5 py-2 text-base transition-colors focus-visible:ring-3 aria-invalid:ring-3 md:text-sm placeholder:text-muted-foreground flex field-sizing-content min-h-16 w-full outline-none disabled:cursor-not-allowed disabled:opacity-50",
				variant === "bare" &&
					"border-0 bg-transparent p-0 shadow-none outline-none focus-visible:ring-0 md:text-base",
				className,
			)}
			{...props}
		/>
	);
}

export { Textarea };
