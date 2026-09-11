import { ChevronLeft, XIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { DrawerClose, DrawerHeader } from "@/components/ui/drawer";

export interface DetailDrawerHeaderProps {
	/** True below the top level, where dismissing returns to the drawer behind rather than the page. */
	nested?: boolean;
	className?: string;
	children: ReactNode;
}

/**
 * The rest of a panel is the primitive's own `DrawerBody`/`DrawerFooter`/`DrawerTitle`, so panels
 * compose rather than fill a fixed set of holes; `nested` is the only prop a Storybook control could
 * drive, so the compound shape costs nothing there.
 *
 * The dismiss is a `DrawerClose`, so it closes through the same path as Escape, an outside press and
 * a swipe rather than a fourth one that can drift.
 *
 * The content row wraps as a backstop; two columns is the rule — `webapp/AGENTS.md` § Panel regions.
 */
export function DetailDrawerHeader({
	nested = false,
	className,
	children,
}: DetailDrawerHeaderProps) {
	return (
		<DrawerHeader className={cn("flex-row items-start gap-3", className)}>
			<DrawerClose render={<Button variant="ghost" size="icon-sm" className="-ml-2 shrink-0" />}>
				{nested ? <ChevronLeft /> : <XIcon />}
				<span className="sr-only">{nested ? "Back" : "Close"}</span>
			</DrawerClose>
			<div className="flex min-w-0 flex-1 flex-wrap items-start gap-x-3 gap-y-2">{children}</div>
		</DrawerHeader>
	);
}
