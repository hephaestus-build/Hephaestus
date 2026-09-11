import { Switch as SwitchPrimitive } from "@base-ui/react/switch";

import { cn } from "cn";

function Switch({
	className,
	size = "default",
	...props
}: SwitchPrimitive.Root.Props & {
	size?: "sm" | "default";
}) {
	// The `::after` box is the hit target (SC 2.5.8): the track is only 18.4px tall, so it is grown on
	// the block axis until it clears 24px. It is deliberately *not* grown on the inline axis. The track
	// is already 32px wide (24px at `sm`), so inline growth buys no conformance — and an absolutely
	// positioned box still counts toward an ancestor's scrollable overflow, so a switch sitting flush
	// against the right edge of a row dragged the whole page 11px sideways at the 320px reflow width
	// (SC 1.4.10). Inline growth also put 12px of invisible switch on top of the description beside it,
	// where it swallowed clicks meant for the text.
	return (
		<SwitchPrimitive.Root
			data-slot="switch"
			data-size={size}
			className={cn(
				"data-checked:bg-primary data-unchecked:bg-input focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:aria-invalid:border-destructive/50 dark:data-unchecked:bg-input/80 shrink-0 rounded-full border border-transparent focus-visible:ring-[3px] aria-invalid:ring-[3px] data-[size=default]:h-[18.4px] data-[size=default]:w-[32px] data-[size=sm]:h-[14px] data-[size=sm]:w-[24px] peer group/switch relative inline-flex items-center transition-all outline-none after:absolute after:inset-x-0 after:-inset-y-2 data-disabled:cursor-not-allowed data-disabled:opacity-50",
				className,
			)}
			{...props}
		>
			<SwitchPrimitive.Thumb
				data-slot="switch-thumb"
				className="bg-background dark:data-unchecked:bg-foreground dark:data-checked:bg-primary-foreground rounded-full group-data-[size=default]/switch:size-4 group-data-[size=sm]/switch:size-3 group-data-[size=default]/switch:data-checked:translate-x-[calc(100%-2px)] group-data-[size=sm]/switch:data-checked:translate-x-[calc(100%-2px)] group-data-[size=default]/switch:data-unchecked:translate-x-0 group-data-[size=sm]/switch:data-unchecked:translate-x-0 pointer-events-none block ring-0 transition-transform"
			/>
		</SwitchPrimitive.Root>
	);
}

export { Switch };
