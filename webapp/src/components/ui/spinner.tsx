import { Loader2Icon } from "lucide-react";

import { cn } from "cn";

/**
 * An indeterminate spinner, for a control the reader just activated. A region gets a skeleton.
 *
 * Decoration by default. A labelled spinner inside a button joins that button's accessible name
 * rather than replacing it — "Save" becomes "Loading Save" — and `role="status"` is a live region
 * besides, so it re-announces every time the button enters its pending state. A caller that genuinely
 * wants the announcement opts in with the standard attributes: pass `role` or `aria-label` and the
 * spinner stops hiding itself.
 */
function Spinner({ className, ...props }: React.ComponentProps<"svg">) {
	const announces = props.role !== undefined || props["aria-label"] !== undefined;
	return (
		<Loader2Icon
			aria-hidden={announces ? undefined : true}
			className={cn("size-4 animate-spin motion-reduce:animate-none", className)}
			{...props}
		/>
	);
}

export { Spinner };
