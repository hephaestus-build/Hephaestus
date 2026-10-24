import { type ComponentProps, useState } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";

/**
 * The primary fill on hover, on keyboard focus and while pressed: `:active` for a pointer, and
 * `data-pressing` for a key, since most browsers apply `:active` to Enter not at all.
 */
const PRESSED_DARK = [
	"hover:border-primary hover:bg-primary hover:text-primary-foreground dark:hover:bg-primary dark:hover:text-primary-foreground",
	"focus-visible:border-primary focus-visible:bg-primary focus-visible:text-primary-foreground dark:focus-visible:bg-primary dark:focus-visible:text-primary-foreground",
	"active:border-primary active:bg-primary active:text-primary-foreground dark:active:bg-primary dark:active:text-primary-foreground",
	"data-pressing:border-primary data-pressing:bg-primary data-pressing:text-primary-foreground dark:data-pressing:bg-primary dark:data-pressing:text-primary-foreground",
].join(" ");

/**
 * The one button of an action a reader takes from a list or a level's foot: the outline button,
 * white with a thin border at rest, dark on hover and keyboard focus and while pressed by pointer,
 * touch or key. The row actions of Reviews of your work and the footer's way across share it, with
 * an icon alone or with words.
 */
export function ActionButton({
	className,
	onPointerDown,
	onPointerUp,
	onPointerLeave,
	onKeyDown,
	onKeyUp,
	onBlur,
	...props
}: Omit<ComponentProps<typeof Button>, "variant">) {
	const [pressing, setPressing] = useState(false);
	return (
		<Button
			variant="outline"
			className={cn(PRESSED_DARK, className)}
			data-pressing={pressing || undefined}
			onPointerDown={(event) => {
				setPressing(true);
				onPointerDown?.(event);
			}}
			onPointerUp={(event) => {
				setPressing(false);
				onPointerUp?.(event);
			}}
			onPointerLeave={(event) => {
				setPressing(false);
				onPointerLeave?.(event);
			}}
			onKeyDown={(event) => {
				if (event.key === "Enter" || event.key === " ") {
					setPressing(true);
				}
				onKeyDown?.(event);
			}}
			onKeyUp={(event) => {
				setPressing(false);
				onKeyUp?.(event);
			}}
			onBlur={(event) => {
				setPressing(false);
				onBlur?.(event);
			}}
			{...props}
		/>
	);
}
