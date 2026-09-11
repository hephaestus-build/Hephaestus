import { Accordion as AccordionPrimitive } from "@base-ui/react/accordion";
import { ChevronDownIcon, ChevronUpIcon } from "lucide-react";

import { cn } from "cn";

function Accordion({ className, ...props }: AccordionPrimitive.Root.Props) {
	return (
		<AccordionPrimitive.Root
			data-slot="accordion"
			className={cn("flex w-full flex-col", className)}
			{...props}
		/>
	);
}

function AccordionItem({ className, ...props }: AccordionPrimitive.Item.Props) {
	return (
		<AccordionPrimitive.Item
			data-slot="accordion-item"
			className={cn("not-last:border-b", className)}
			{...props}
		/>
	);
}

function AccordionTrigger({ className, children, ...props }: AccordionPrimitive.Trigger.Props) {
	return (
		// `w-full min-w-0 flex-1`, not the bare `flex` shadcn ships. Base UI's Header renders an `<h3>`
		// wrapping the trigger, so a caller's `<AccordionTrigger className="…">` lands on the button
		// *inside* it: wherever the header is the layout item rather than a full-width block — a flex
		// row, a grid cell — the trigger is only as wide as its own text and the caller cannot widen it
		// from outside. Sizing the header here restores that control, and is inert in block flow.
		<AccordionPrimitive.Header className="flex w-full min-w-0 flex-1">
			<AccordionPrimitive.Trigger
				data-slot="accordion-trigger"
				// `aria-disabled:`, never `disabled:`: Base UI keeps a disabled trigger focusable, so it
				// sets `aria-disabled` and omits the native attribute — a `disabled:` rule compiles to
				// `&:disabled` and matches nothing, leaving a disabled item looking entirely live.
				className={cn(
					"focus-visible:ring-ring/50 focus-visible:border-ring focus-visible:after:border-ring **:data-[slot=accordion-trigger-icon]:text-muted-foreground rounded-lg py-2.5 text-left text-sm font-medium hover:underline focus-visible:ring-[3px] **:data-[slot=accordion-trigger-icon]:ml-auto **:data-[slot=accordion-trigger-icon]:size-4 group/accordion-trigger relative flex min-w-0 flex-1 items-start justify-between border border-transparent transition-all outline-none aria-disabled:pointer-events-none aria-disabled:opacity-50",
					className,
				)}
				{...props}
			>
				{children}
				<ChevronDownIcon
					data-slot="accordion-trigger-icon"
					className="pointer-events-none shrink-0 group-aria-expanded/accordion-trigger:hidden"
				/>
				<ChevronUpIcon
					data-slot="accordion-trigger-icon"
					className="pointer-events-none hidden shrink-0 group-aria-expanded/accordion-trigger:inline"
				/>
			</AccordionPrimitive.Trigger>
		</AccordionPrimitive.Header>
	);
}

function AccordionContent({ className, children, ...props }: AccordionPrimitive.Panel.Props) {
	return (
		<AccordionPrimitive.Panel
			data-slot="accordion-content"
			className="data-open:animate-accordion-down data-closed:animate-accordion-up text-sm overflow-hidden"
			{...props}
		>
			<div
				className={cn(
					"pt-0 pb-2.5 [&_a]:hover:text-foreground h-(--accordion-panel-height) data-ending-style:h-0 data-starting-style:h-0 [&_a]:underline [&_a]:underline-offset-3 [&_p:not(:last-child)]:mb-4",
					className,
				)}
			>
				{children}
			</div>
		</AccordionPrimitive.Panel>
	);
}

export { Accordion, AccordionContent, AccordionItem, AccordionTrigger };
