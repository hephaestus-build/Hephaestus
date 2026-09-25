import type { ComponentProps } from "react";

import { cn } from "cn";
import type { BadgeVariant } from "@/components/common/status-def";
import { Button } from "@/components/ui/button";

/** What a response says: agreeing, disputing, or neither. */
export type ResponseTone = "positive" | "negative" | "neutral";

/**
 * The tone a response wears, read off its registry entry's badge variant: the success green
 * agrees, the destructive red and the warning amber both dispute — "Not helpful" and "Disputed"
 * press the same red — and every other variant says neither.
 */
export function toneOf(variant: BadgeVariant): ResponseTone {
	switch (variant) {
		case "success": {
			return "positive";
		}
		case "destructive":
		case "warning": {
			return "negative";
		}
		case "default":
		case "outline":
		case "secondary": {
			return "neutral";
		}
	}
}

/**
 * A pressed response is tinted in its own colour — light green for the agreeing one, light red for
 * the disputing one, the muted ground for the one that says neither — on the ground, the text, the
 * icon and the border, so the choices read as opposites and none is mistaken for an unpressed
 * outline in either theme. The tints are the badge primitive's: the colour at a tenth over the
 * ground, a fifth in the dark theme. The dark-mode outline paints its own ground and border, so
 * both are restated here.
 *
 * Every utility carries the `aria-pressed:` prefix, as `toggle.tsx` does: the outline variant's own
 * `aria-pressed:bg-muted` is an attribute selector, which outranks a bare utility whatever the
 * order, and only a utility in the same variant group is the one tailwind-merge replaces.
 */
const PRESSED_TINT: Record<ResponseTone, string> = {
	positive:
		"aria-pressed:border-success/40 aria-pressed:bg-success/10 aria-pressed:text-success aria-pressed:hover:bg-success/15 aria-pressed:hover:text-success dark:aria-pressed:border-success/40 dark:aria-pressed:bg-success/20 dark:aria-pressed:hover:bg-success/25",
	negative:
		"aria-pressed:border-destructive/40 aria-pressed:bg-destructive/10 aria-pressed:text-destructive aria-pressed:hover:bg-destructive/15 aria-pressed:hover:text-destructive dark:aria-pressed:border-destructive/40 dark:aria-pressed:bg-destructive/20 dark:aria-pressed:hover:bg-destructive/25",
	neutral:
		"aria-pressed:border-foreground/25 aria-pressed:bg-muted aria-pressed:text-foreground aria-pressed:hover:bg-muted aria-pressed:hover:text-foreground dark:aria-pressed:border-foreground/25 dark:aria-pressed:bg-muted dark:aria-pressed:hover:bg-muted",
};

export interface ResponseButtonProps extends Omit<ComponentProps<typeof Button>, "variant"> {
	tone: ResponseTone;
	/** Whether this is the response the reader chose; a pressed button wears its tone. */
	pressed: boolean;
}

/** One of the responses a reader can give: a card's "Helpful", an observation's "Addressed". */
export function ResponseButton({ tone, pressed, className, ...props }: ResponseButtonProps) {
	return (
		<Button
			type="button"
			variant="outline"
			aria-pressed={pressed}
			className={cn(PRESSED_TINT[tone], className)}
			{...props}
		/>
	);
}
