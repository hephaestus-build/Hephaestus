import type { ComponentProps } from "react";

import { cn } from "cn";

/**
 * The bulleted list every practice surface draws the same way: a disc marker in the muted grey,
 * so the mark structures the items without competing with the text or the links in them.
 */
export function BulletList({ className, ...props }: ComponentProps<"ul">) {
	return (
		<ul
			className={cn("list-disc space-y-1 pl-4 marker:text-muted-foreground", className)}
			{...props}
		/>
	);
}
