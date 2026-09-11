import type * as React from "react";

import { cn } from "cn";

/**
 * Semantic `<h2>` card title, used inside `CardHeader`. `CardTitle` renders a `<div>` and takes no
 * `render` prop, but these settings pages are navigated by heading, so each section title has to be a
 * real `<h2>` under the page `<h1>`. Mirrors `CardTitle`'s styling so the two look identical.
 */
export function IntegrationCardHeading({
	className,
	children,
	...props
}: React.ComponentProps<"h2">) {
	return (
		<h2
			data-slot="card-title"
			className={cn("text-base leading-snug font-medium", className)}
			{...props}
		>
			{children}
		</h2>
	);
}
