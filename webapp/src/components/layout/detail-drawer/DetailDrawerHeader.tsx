import { ChevronLeft, XIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { DrawerClose, DrawerHeader } from "@/components/ui/drawer";

import { DetailPath, type DetailPathProps } from "./DetailPath";

export interface DetailDrawerHeaderProps {
	/** True below the top level, where dismissing returns to the drawer behind rather than the page. */
	nested?: boolean;
	/**
	 * Where the dismiss sits. `beside` (the default) gives it a column of its own, so the content
	 * starts after it. `inPath` lays it over the start of the content's first row, which must be a
	 * {@link LevelPathRow} to leave room for it, so the title and everything under it line up with
	 * the body.
	 */
	dismiss?: "beside" | "inPath";
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
 * The content row wraps as a backstop; two columns is the rule — `webapp/DESIGN.md` § Panel regions.
 */
export function DetailDrawerHeader({
	nested = false,
	dismiss = "beside",
	className,
	children,
}: DetailDrawerHeaderProps) {
	const inPath = dismiss === "inPath";
	return (
		<DrawerHeader className={cn("flex-row items-start gap-3", inPath && "relative", className)}>
			<DrawerClose
				render={
					<Button
						variant="ghost"
						size="icon-sm"
						className={inPath ? "absolute top-5 left-4" : "-ml-2 shrink-0"}
					/>
				}
			>
				{nested ? <ChevronLeft /> : <XIcon />}
				<span className="sr-only">{nested ? "Back" : "Close"}</span>
			</DrawerClose>
			<div className="flex min-w-0 flex-1 flex-wrap items-start gap-x-3 gap-y-2">{children}</div>
		</DrawerHeader>
	);
}

/**
 * The path's row of a header with `dismiss="inPath"`: as tall as the dismiss, and indented past it,
 * so the dismiss sits over the row's start and never over the first crumb.
 */
export function LevelPathRow(props: DetailPathProps) {
	return (
		<div className="flex min-h-7 items-center pl-8">
			<DetailPath {...props} />
		</div>
	);
}
