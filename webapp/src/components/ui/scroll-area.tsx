import { ScrollArea as ScrollAreaPrimitive } from "@base-ui/react/scroll-area";
import type { Ref } from "react";

import { cn } from "cn";

function ScrollArea({
	className,
	viewportClassName,
	children,
	viewportRef,
	...props
}: ScrollAreaPrimitive.Root.Props & {
	/** Ref to the scrolling Viewport (the element that actually overflows) — needed for scroll-position
	 * logic like auto-scroll/scroll-to-bottom. A ref on Root would never observe scroll events. */
	viewportRef?: Ref<HTMLDivElement>;
	/**
	 * Classes for the Viewport — the element that actually overflows. This is where a bounding height
	 * belongs: the Viewport defaults to `size-full` (height: 100%), which only resolves against an
	 * ancestor with a *definite* height, so a `max-h-*` on the Root (or a `max-h` flex column around it)
	 * never clips and the content renders full-height. A `max-h-*` here caps the Viewport's own box, so
	 * it grows to content and then scrolls — no fixed-height ancestor required.
	 */
	viewportClassName?: string;
}) {
	return (
		<ScrollAreaPrimitive.Root
			data-slot="scroll-area"
			className={cn("relative", className)}
			{...props}
		>
			<ScrollAreaPrimitive.Viewport
				ref={viewportRef}
				data-slot="scroll-area-viewport"
				className={cn(
					"focus-visible:ring-ring/50 size-full rounded-[inherit] transition-[color,box-shadow] outline-none focus-visible:ring-[3px] focus-visible:outline-1",
					viewportClassName,
				)}
			>
				{children}
			</ScrollAreaPrimitive.Viewport>
			<ScrollBar />
			<ScrollAreaPrimitive.Corner />
		</ScrollAreaPrimitive.Root>
	);
}

function ScrollBar({
	className,
	orientation = "vertical",
	...props
}: ScrollAreaPrimitive.Scrollbar.Props) {
	return (
		<ScrollAreaPrimitive.Scrollbar
			data-slot="scroll-area-scrollbar"
			orientation={orientation}
			// Match the axis on `data-orientation`, never `data-horizontal`: Base UI writes non-boolean
			// state as `data-<key>="<value>"`, so a `data-horizontal:` variant compiles to
			// `[data-horizontal]` and matches nothing — and these utilities are the scrollbar's whole
			// box, so it would render as a bare strip of padding with no width and no border.
			className={cn(
				"data-[orientation=horizontal]:h-2.5 data-[orientation=horizontal]:flex-col data-[orientation=horizontal]:border-t data-[orientation=horizontal]:border-t-transparent data-[orientation=vertical]:h-full data-[orientation=vertical]:w-2.5 data-[orientation=vertical]:border-l data-[orientation=vertical]:border-l-transparent flex touch-none p-px transition-colors select-none",
				className,
			)}
			{...props}
		>
			<ScrollAreaPrimitive.Thumb
				data-slot="scroll-area-thumb"
				className="rounded-full bg-border relative flex-1"
			/>
		</ScrollAreaPrimitive.Scrollbar>
	);
}

export { ScrollArea, ScrollBar };
