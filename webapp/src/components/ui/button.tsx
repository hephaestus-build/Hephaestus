import { Button as ButtonPrimitive } from "@base-ui/react/button";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add button` drops the following; re-apply them.
 *
 * 1. `quiet`, the ghost that rests at muted contrast for an action beside the content it acts on,
 *    and comes up to full contrast on hover.
 * 2. `destructive-outline` and `warning-outline`, one outlined shape in each status tone.
 * 3. `size="inline"`, a button that sits inside a sentence.
 * 4. The `shape` axis.
 * 5. `aria-pressed` is styled wherever `aria-expanded` is: a pressed toggle rendered as a Button has
 *    to look pressed, and upstream styles only the expanded case.
 * 6. `mentor`, the accent a practice surface carries at most one of — `webapp/AGENTS.md`
 *    § Practice surfaces palette owns when a surface may wear it.
 */
const buttonVariants = cva(
	"group/button inline-flex shrink-0 items-center justify-center rounded-lg border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-all outline-none select-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
	{
		variants: {
			variant: {
				// Plain `hover:`, not `[a]:hover:` — that variant compiles to `&:is(a):hover`, which gives
				// hover feedback to a button rendered as a link and to no other.
				default: "bg-primary text-primary-foreground hover:bg-primary/80",
				outline:
					"border-border bg-background hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground aria-pressed:bg-muted aria-pressed:text-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
				secondary:
					"bg-secondary text-secondary-foreground hover:bg-secondary/80 aria-expanded:bg-secondary aria-expanded:text-secondary-foreground aria-pressed:bg-secondary aria-pressed:text-secondary-foreground",
				ghost:
					"hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground aria-pressed:bg-muted aria-pressed:text-foreground dark:hover:bg-muted/50",
				quiet:
					"text-muted-foreground hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground aria-pressed:bg-muted aria-pressed:text-foreground dark:hover:bg-muted/50",
				destructive:
					"bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/20 dark:bg-destructive/70 dark:focus-visible:ring-destructive/40",
				"destructive-outline":
					"border-destructive/30 bg-background text-destructive hover:bg-destructive/10 hover:text-destructive focus-visible:ring-destructive/20 dark:bg-input/30 dark:hover:bg-destructive/20 dark:focus-visible:ring-destructive/40",
				"warning-outline":
					"border-warning/30 bg-background text-warning hover:bg-warning/10 hover:text-warning focus-visible:ring-warning/20 dark:bg-input/30 dark:hover:bg-warning/20 dark:focus-visible:ring-warning/40",
				link: "text-primary underline-offset-4 hover:underline",
				mentor: "bg-mentor text-mentor-foreground hover:bg-mentor/90",
			},
			size: {
				default:
					"h-8 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
				xs: "h-6 gap-1 rounded-[min(var(--radius-md),10px)] px-2 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
				sm: "h-7 gap-1 rounded-[min(var(--radius-md),12px)] px-2.5 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3.5",
				lg: "h-9 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-3 has-data-[icon=inline-start]:pl-3",
				// A button that sits inside a sentence: no height, no padding box, and the type size inherited
				// from the prose around it. `text-[length:inherit]` and not `text-inherit` — that one sets
				// the *colour*, and since it wins the merge against a variant it repaints `variant="link"`
				// from `text-primary` to the surrounding grey.
				inline: "h-auto gap-1 rounded-sm p-0 text-[length:inherit] font-normal",
				icon: "size-8",
				"icon-xs":
					"size-6 rounded-[min(var(--radius-md),10px)] in-data-[slot=button-group]:rounded-lg [&_svg:not([class*='size-'])]:size-3",
				"icon-sm":
					"size-7 rounded-[min(var(--radius-md),12px)] in-data-[slot=button-group]:rounded-lg",
				"icon-lg": "size-9",
			},
			// Applied after `size`, so the pill overrides whatever radius the size chose.
			shape: {
				default: "",
				pill: "rounded-full",
			},
		},
		defaultVariants: {
			variant: "default",
			size: "default",
			shape: "default",
		},
	},
);

function Button({
	className,
	variant = "default",
	size = "default",
	shape = "default",
	...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
	return (
		<ButtonPrimitive
			data-slot="button"
			className={cn(buttonVariants({ variant, size, shape, className }))}
			{...props}
		/>
	);
}

export { Button, buttonVariants };
