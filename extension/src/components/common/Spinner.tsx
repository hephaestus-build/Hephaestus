import { Loader2Icon } from "lucide-react";
import type { ComponentProps } from "react";

import { cn } from "cn";

/**
 * An indeterminate spinner, for a control the reader just activated; a region gets a skeleton.
 * Decoration unless the caller names it, as the webapp's `Spinner` does.
 */
export function Spinner({ className, ...props }: ComponentProps<"svg">) {
	const announces = props.role !== undefined || props["aria-label"] !== undefined;
	return (
		<Loader2Icon
			aria-hidden={announces ? undefined : true}
			className={cn("size-4 animate-spin motion-reduce:animate-none", className)}
			{...props}
		/>
	);
}
