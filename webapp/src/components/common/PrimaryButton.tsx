import type { ComponentProps } from "react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * The primary button of a practice surface, in the accent: "See all practices" on the page,
 * "Send" in an open comment band. The palette (`webapp/AGENTS.md` § Practice surfaces palette)
 * is why a surface carries one at rest — a band that opens brings its own beside it — and why
 * nothing else on it is this colour.
 */
export function PrimaryButton({
	className,
	...props
}: Omit<ComponentProps<typeof Button>, "variant">) {
	return (
		<Button
			className={cn("bg-mentor text-mentor-foreground hover:bg-mentor/90", className)}
			{...props}
		/>
	);
}
