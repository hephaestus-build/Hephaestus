import type { ComponentProps } from "react";

import { cn } from "cn";

export type ButtonVariant = "default" | "outline" | "ghost" | "destructive-outline" | "mentor";
export type ButtonSize = "default" | "sm" | "lg" | "icon";

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
	default: "bg-primary text-primary-foreground hover:bg-primary/85",
	outline: "border-border bg-background shadow-xs hover:bg-muted",
	ghost: "hover:bg-muted",
	"destructive-outline":
		"border-destructive/30 bg-background text-destructive hover:bg-destructive/10",
	mentor: "bg-mentor text-mentor-foreground shadow-xs hover:bg-mentor/90",
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
	default: "h-8 gap-1.5 px-3 text-sm",
	sm: "h-7 gap-1 px-2.5 text-xs",
	lg: "h-10 gap-2 px-4 text-sm",
	icon: "size-8",
};

/** The button look, for a link that acts as a button (`ExternalLink`). */
export function buttonClasses(variant: ButtonVariant = "default", size: ButtonSize = "default") {
	return cn(
		"inline-flex shrink-0 items-center justify-center rounded-lg border border-transparent font-medium whitespace-nowrap outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0",
		VARIANT_CLASSES[variant],
		SIZE_CLASSES[size],
	);
}

export interface ButtonProps extends ComponentProps<"button"> {
	variant?: ButtonVariant;
	size?: ButtonSize;
}

/**
 * The webapp's button look (`webapp/src/components/ui/button.tsx`) on a native `<button>`, without
 * Base UI: the extension has a handful of controls, none of which needs a slot.
 */
export function Button({
	variant = "default",
	size = "default",
	className,
	type = "button",
	...props
}: ButtonProps) {
	return (
		<button
			// oxlint-disable-next-line react/button-has-type -- The prop defaults to "button" above.
			type={type}
			data-variant={variant}
			className={cn(buttonClasses(variant, size), className)}
			{...props}
		/>
	);
}
