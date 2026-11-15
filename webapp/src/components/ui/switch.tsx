import { Switch as SwitchPrimitive } from "@base-ui/react/switch";

import { cn } from "cn";
import { Spinner } from "@/components/ui/spinner";
import { usePendingSpinner } from "@/lib/use-pending-spinner";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add switch` drops the following; re-apply them.
 *
 * 1. `pending`, for a switch whose change is in flight. At once, the switch turns read-only, not
 *    disabled: a disabled switch leaves the tab order and drops the focus of the reader who just
 *    pressed it. After a second, a `<Spinner />` shows in the thumb, as `Button`'s `pending` does and
 *    for the reason `webapp/DESIGN.md` § Loading and errors gives.
 */
function Switch({
	className,
	size = "default",
	pending = false,
	readOnly = false,
	...props
}: SwitchPrimitive.Root.Props & {
	size?: "sm" | "default";
	pending?: boolean;
}) {
	const showSpinner = usePendingSpinner(pending);
	// The `::after` box is the hit target (SC 2.5.8): the track is only 18.4px tall, so it is grown on
	// the block axis until it clears 24px. It is deliberately *not* grown on the inline axis. The track
	// is already 32px wide (24px at `sm`), so inline growth buys no conformance — and an absolutely
	// positioned box still counts toward an ancestor's scrollable overflow, so a switch sitting flush
	// against the right edge of a row dragged the whole page 11px sideways at the 320px reflow width
	// (SC 1.4.10). Inline growth also put 12px of invisible switch on top of the description beside it,
	// where it swallowed clicks meant for the text.
	//
	// In a choice card the card takes the focus ring, so a valid track drops its own. The resets are
	// spelled as in `checkbox.tsx`, for the reason there.
	return (
		<SwitchPrimitive.Root
			data-slot="switch"
			data-size={size}
			className={cn(
				"peer group/switch relative inline-flex shrink-0 items-center rounded-full border border-transparent transition-all outline-none after:absolute after:inset-x-0 after:-inset-y-2 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 group-has-[>[data-slot=field]]/field-label:focus-visible:not-aria-invalid:border-transparent group-has-[>[data-slot=field]]/field-label:focus-visible:not-aria-invalid:ring-0 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 data-[size=default]:h-[18.4px] data-[size=default]:w-[32px] data-[size=sm]:h-[14px] data-[size=sm]:w-[24px] dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 data-checked:bg-primary data-unchecked:bg-input dark:data-unchecked:bg-input/80 data-disabled:cursor-not-allowed data-disabled:opacity-50",
				className,
			)}
			readOnly={readOnly || pending}
			{...props}
		>
			<SwitchPrimitive.Thumb
				data-slot="switch-thumb"
				className="pointer-events-none flex items-center justify-center rounded-full bg-background ring-0 transition-transform group-data-[size=default]/switch:size-4 group-data-[size=sm]/switch:size-3 group-data-[size=default]/switch:data-checked:translate-x-[calc(100%-2px)] group-data-[size=sm]/switch:data-checked:translate-x-[calc(100%-2px)] dark:data-checked:bg-primary-foreground group-data-[size=default]/switch:data-unchecked:translate-x-0 group-data-[size=sm]/switch:data-unchecked:translate-x-0 dark:data-unchecked:bg-foreground"
			>
				{/* Inside the thumb, so the switch never moves under the pointer when it shows. */}
				{showSpinner && <Spinner data-slot="spinner" className="size-3" />}
			</SwitchPrimitive.Thumb>
		</SwitchPrimitive.Root>
	);
}

export { Switch };
