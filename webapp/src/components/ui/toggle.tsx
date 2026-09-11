import { Toggle as TogglePrimitive } from "@base-ui/react/toggle";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "cn";

// One state, one channel, so no two of them can look alike: hover owns the background, selection owns
// the border colour and the type weight, focus-visible owns the ring, disabled owns the opacity.
// Selection must never move back onto `bg-*` — `--muted`, `--accent` and `--secondary` are the same
// colour in this theme, so a pressed background is a hovered background. The weight is what carries
// selection without colour, which is what WCAG 2.2 SC 1.4.1 requires of a state a hue would otherwise
// be the only sign of. `aria-disabled` as well as `disabled`: a standalone toggle carries only the
// native attribute, while one inside a group carries both, so neither variant covers the pair alone.
const toggleVariants = cva(
	"group/toggle inline-flex items-center justify-center gap-1 rounded-lg border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap outline-none transition-all hover:bg-muted hover:text-foreground aria-pressed:z-10 aria-pressed:border-primary aria-pressed:font-semibold aria-pressed:text-foreground focus-visible:ring-ring/50 focus-visible:ring-[3px] aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
	{
		variants: {
			variant: {
				default: "bg-transparent",
				outline: "border-input bg-transparent",
			},
			size: {
				default: "h-8 min-w-8 px-2",
				sm: "h-7 min-w-7 rounded-[min(var(--radius-md),12px)] px-1.5 text-[0.8rem]",
				lg: "h-9 min-w-9 px-2.5",
			},
		},
		defaultVariants: {
			variant: "default",
			size: "default",
		},
	},
);

function Toggle({
	className,
	variant = "default",
	size = "default",
	...props
}: TogglePrimitive.Props & VariantProps<typeof toggleVariants>) {
	return (
		<TogglePrimitive
			data-slot="toggle"
			className={cn(toggleVariants({ variant, size, className }))}
			{...props}
		/>
	);
}

export { Toggle, toggleVariants };
