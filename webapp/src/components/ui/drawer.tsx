"use client";

import { Drawer as DrawerPrimitive } from "@base-ui/react/drawer";
import { cva, type VariantProps } from "class-variance-authority";
import type * as React from "react";
import { createContext, useContext } from "react";

import { cn } from "cn";

type DrawerContextProps = {
	hasSnapPoints: boolean;
	modal: DrawerPrimitive.Root.Props["modal"];
	showSwipeHandle: boolean;
	swipeDirection: NonNullable<DrawerPrimitive.Root.Props["swipeDirection"]>;
};

const DrawerContext = createContext<DrawerContextProps | null>(null);

function useDrawer() {
	const context = useContext(DrawerContext);
	if (!context) {
		throw new Error("useDrawer must be used within a Drawer.");
	}
	return context;
}

function Drawer({
	modal = true,
	showSwipeHandle = false,
	snapPoints,
	swipeDirection = "down",
	...props
}: DrawerPrimitive.Root.Props & {
	showSwipeHandle?: boolean;
}) {
	const hasSnapPoints = snapPoints != null && snapPoints.length > 0;
	const contextValue = { hasSnapPoints, modal, showSwipeHandle, swipeDirection };

	return (
		<DrawerContext.Provider value={contextValue}>
			<DrawerPrimitive.Root
				data-slot="drawer"
				modal={modal}
				snapPoints={snapPoints}
				swipeDirection={swipeDirection}
				{...props}
			/>
		</DrawerContext.Provider>
	);
}

function DrawerTrigger({ ...props }: DrawerPrimitive.Trigger.Props) {
	return <DrawerPrimitive.Trigger data-slot="drawer-trigger" {...props} />;
}

function DrawerPortal({ ...props }: DrawerPrimitive.Portal.Props) {
	return <DrawerPrimitive.Portal data-slot="drawer-portal" {...props} />;
}

function DrawerClose({ ...props }: DrawerPrimitive.Close.Props) {
	return <DrawerPrimitive.Close data-slot="drawer-close" {...props} />;
}

function DrawerOverlay({ className, ...props }: DrawerPrimitive.Backdrop.Props) {
	return (
		<DrawerPrimitive.Backdrop
			data-slot="drawer-overlay"
			// Dimmed but never blurred. A side panel earns its place over a page transition because
			// the page behind stays legible; blurring it removes the only advantage.
			className={cn(
				"fixed inset-0 z-50 min-h-dvh bg-black/25 opacity-[max(var(--drawer-overlay-min-opacity,0),calc(1-var(--drawer-swipe-progress)))] transition-opacity duration-450 ease-[cubic-bezier(0.32,0.72,0,1)] select-none data-ending-style:pointer-events-none data-ending-style:opacity-0 data-ending-style:duration-[calc(var(--drawer-swipe-strength)*400ms)] data-snap-points:[--drawer-overlay-min-opacity:0.5] data-starting-style:opacity-0 data-swiping:duration-0 supports-[-webkit-touch-callout:none]:absolute",
				className,
			)}
			{...props}
		/>
	);
}

function DrawerSwipeHandle({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="drawer-swipe-handle"
			aria-hidden="true"
			className={cn(
				"relative z-10 flex shrink-0 cursor-grab items-center justify-center transition-opacity duration-200 group-data-nested-drawer-open/drawer-popup:opacity-0 group-data-nested-drawer-swiping/drawer-popup:opacity-100 group-data-[swipe-direction=left]/drawer-popup:order-last group-data-[swipe-direction=up]/drawer-popup:order-last active:cursor-grabbing",
				"group-data-[swipe-axis=y]/drawer-popup:h-5 group-data-[swipe-axis=x]/drawer-popup:w-5",
				"before:rounded-full before:bg-border group-data-[swipe-axis=y]/drawer-popup:before:h-1 group-data-[swipe-axis=y]/drawer-popup:before:w-10 group-data-[swipe-axis=x]/drawer-popup:before:h-10 group-data-[swipe-axis=x]/drawer-popup:before:w-1",
				className,
			)}
			{...props}
		/>
	);
}

const drawerContentVariants = cva("", {
	variants: {
		size: {
			default:
				"[--peek:1rem] data-[swipe-axis=x]:[--drawer-content-width:75%] data-[swipe-axis=x]:sm:[--drawer-content-width:24rem]",
			/**
			 * A panel that replaces a page, so it has to hold what that page held. Full width below
			 * `sm`, where a partial cover is unreadable. `--peek` is far above the default: the column
			 * a covered panel keeps on screen is the reason to stack rather than replace.
			 */
			detail:
				"[--peek:6rem] data-[swipe-axis=x]:[--drawer-content-width:100%] data-[swipe-axis=x]:sm:[--drawer-content-width:min(44rem,92vw)] data-[swipe-axis=x]:xl:[--drawer-content-width:min(62rem,75vw)]",
		},
	},
	defaultVariants: { size: "default" },
});

/**
 * `dimWhenNested` is the one deliberate departure from the upstream shadcn drawer, which fades a
 * covered drawer's content to nothing. That reads correctly for a bottom sheet, where only a sliver
 * of the parent shows; a wide side panel leaves a real column of the parent on screen, and an empty
 * column is worse than a readable one.
 */
function DrawerContent({
	className,
	children,
	dimWhenNested = true,
	size,
	...props
}: DrawerPrimitive.Popup.Props &
	VariantProps<typeof drawerContentVariants> & { dimWhenNested?: boolean }) {
	const { hasSnapPoints, modal, showSwipeHandle, swipeDirection } = useDrawer();
	const swipeAxis = swipeDirection === "down" || swipeDirection === "up" ? "y" : "x";

	return (
		<DrawerPortal>
			{modal === true && <DrawerOverlay data-snap-points={hasSnapPoints ? "" : undefined} />}
			<DrawerPrimitive.Viewport
				data-slot="drawer-viewport"
				data-modal={modal}
				// `overflow-hidden`: the popup's `--bleed` pseudo-element deliberately extends past the
				// edge it is anchored to, and clipping it here keeps it off the page's scroll width.
				className="pointer-events-none fixed inset-0 z-50 overflow-hidden select-none data-[modal=true]:pointer-events-auto"
			>
				<DrawerPrimitive.Popup
					data-slot="drawer-popup"
					data-swipe-axis={swipeAxis}
					data-snap-points={hasSnapPoints ? "" : undefined}
					className={cn(
						// `height` is transitioned only on the y axis. Base UI releases a parent's pinned height on the
						// exact frame its child's exit completes, so a side panel that animates height jumps at that
						// frame; on the x axis it is `inset-y-0` and its height never legitimately changes.
						"group/drawer-popup pointer-events-auto fixed z-50 m-(--drawer-inset,0px) flex max-h-(--drawer-content-max-height,none) min-h-0 w-(--drawer-content-width,auto) transform-[translate3d(var(--translate-x,0px),var(--translate-y,0px),0)_scale(var(--stack-scale))] flex-col bg-popover text-popover-foreground shadow-lg ease-(--drawer-ease) will-change-transform outline-none select-none",
						"data-[swipe-axis=y]:h-(--drawer-content-height) data-[swipe-axis=y]:transition-[transform,height,opacity,filter] data-[swipe-axis=y]:[interpolate-size:allow-keywords]",
						"data-[swipe-axis=x]:transition-[transform,opacity,filter]",
						"data-nested-drawer-open:overflow-hidden data-nested-drawer-open:brightness-95",
						// Bleed — paints past the anchored edge so an overscrolled drawer shows no gap.
						"after:pointer-events-none after:absolute after:bg-(--drawer-bleed-background,var(--color-popover)) data-[swipe-axis=x]:after:inset-y-0 data-[swipe-axis=x]:after:w-(--bleed) data-[swipe-axis=y]:after:inset-x-0 data-[swipe-axis=y]:after:h-(--bleed) data-[swipe-direction=down]:after:top-full data-[swipe-direction=left]:after:right-full data-[swipe-direction=right]:after:left-full data-[swipe-direction=up]:after:bottom-full",
						"[--drawer-content-height:var(--drawer-height,auto)] data-[swipe-axis=y]:[--drawer-content-max-height:calc(100dvh-6rem)] data-[swipe-axis=y]:data-snap-points:[--drawer-content-height:100dvh]",
						drawerContentVariants({ size }),
						// Reduced motion: keep the panel, drop what triggers vestibular symptoms. The scale and
						// the step-back go to zero and the panel fades instead of travelling its own width.
						"motion-reduce:[--stack-step:0] motion-reduce:[--peek:0px] motion-reduce:[--closed-transform:none] motion-reduce:data-ending-style:opacity-0 motion-reduce:data-starting-style:opacity-0",
						// Stack — each nested drawer steps the ones behind it back by `--stack-step`.
						"[--bleed:3rem] [--stack-height:var(--drawer-frontmost-height,var(--drawer-height,0px))] [--stack-peek-offset:max(0px,calc((var(--nested-drawers)-var(--stack-progress))*var(--peek)))] [--stack-progress:clamp(0,var(--drawer-swipe-progress),1)] [--stack-scale-base:max(0,calc(1-(var(--nested-drawers)*var(--stack-step))))] [--stack-scale:clamp(0,calc(var(--stack-scale-base)+(var(--stack-step)*var(--stack-progress))),1)] [--stack-shrink:calc(1-var(--stack-scale))] [--stack-step:0.05]",
						"[--drawer-ease:cubic-bezier(0.05,0.7,0.1,1)] [--drawer-enter:280ms] [--drawer-exit:calc(var(--drawer-swipe-strength)*200ms)] duration-(--drawer-enter)",
						"data-ending-style:transform-(--closed-transform) data-ending-style:opacity-[0.9999] data-ending-style:duration-(--drawer-exit) data-ending-style:[--drawer-ease:cubic-bezier(0.2,0,0.38,0.9)] data-nested-drawer-swiping:duration-0 data-ending-style:data-nested-drawer-swiping:duration-(--drawer-exit) data-starting-style:transform-(--closed-transform) data-swiping:duration-0 data-ending-style:data-swiping:duration-(--drawer-exit)",
						"data-[swipe-axis=y]:inset-x-0 data-[swipe-axis=y]:data-nested-drawer-open:h-(--stack-height)",
						"data-[swipe-axis=x]:inset-y-0 data-[swipe-axis=x]:flex-row",
						"data-[swipe-direction=down]:bottom-0 data-[swipe-direction=down]:origin-bottom data-[swipe-direction=down]:rounded-t-xl data-[swipe-direction=down]:border-t data-[swipe-direction=down]:[--closed-transform:translate3d(0,calc(100%+var(--drawer-inset,0px)+2px),0)] data-[swipe-direction=down]:[--translate-y:calc(var(--drawer-snap-point-offset,0px)+var(--drawer-swipe-movement-y)-var(--stack-peek-offset)-(var(--stack-shrink)*var(--stack-height)))]",
						"data-[swipe-direction=up]:top-0 data-[swipe-direction=up]:origin-top data-[swipe-direction=up]:rounded-b-xl data-[swipe-direction=up]:border-b data-[swipe-direction=up]:[--closed-transform:translate3d(0,calc(-100%-var(--drawer-inset,0px)-2px),0)] data-[swipe-direction=up]:[--translate-y:calc(var(--drawer-snap-point-offset,0px)+var(--drawer-swipe-movement-y)+var(--stack-peek-offset)+(var(--stack-shrink)*var(--stack-height)))]",
						"data-[swipe-direction=left]:left-0 data-[swipe-direction=left]:origin-left data-[swipe-direction=left]:rounded-r-xl data-[swipe-direction=left]:border-r data-[swipe-direction=left]:[--closed-transform:translate3d(calc(-100%-var(--drawer-inset,0px)-2px),0,0)] data-[swipe-direction=left]:[--translate-x:calc(var(--drawer-swipe-movement-x)+var(--stack-peek-offset)+(var(--stack-shrink)*100%))]",
						"data-[swipe-direction=right]:right-0 data-[swipe-direction=right]:origin-right data-[swipe-direction=right]:rounded-l-xl data-[swipe-direction=right]:border-l data-[swipe-direction=right]:[--closed-transform:translate3d(calc(100%+var(--drawer-inset,0px)+2px),0,0)] data-[swipe-direction=right]:[--translate-x:calc(var(--drawer-swipe-movement-x)-var(--stack-peek-offset)-(var(--stack-shrink)*100%))]",
						className,
					)}
					{...props}
				>
					{showSwipeHandle && <DrawerSwipeHandle />}
					<DrawerPrimitive.Content
						data-slot="drawer-content"
						className={cn(
							"flex min-h-0 flex-1 flex-col overflow-hidden overscroll-contain rounded-[inherit] transition-opacity duration-300 ease-[cubic-bezier(0.45,1.005,0,1.005)] select-text group-data-swiping/drawer-popup:select-none",
							dimWhenNested &&
								"group-data-nested-drawer-open/drawer-popup:opacity-0 group-data-nested-drawer-swiping/drawer-popup:opacity-100",
						)}
					>
						{children}
					</DrawerPrimitive.Content>
				</DrawerPrimitive.Popup>
			</DrawerPrimitive.Viewport>
		</DrawerPortal>
	);
}

function DrawerHeader({ className, ...props }: React.ComponentProps<"div">) {
	return (
		// `shrink-0`: `DrawerBody` is `flex-1` off a zero basis, so the header is what would be squashed.
		<div
			data-slot="drawer-header"
			className={cn("flex shrink-0 flex-col gap-1 px-6 pt-5 pb-4", className)}
			{...props}
		/>
	);
}

/**
 * The scrollable middle of a drawer. `min-h-0` is load-bearing — a flex item's automatic minimum
 * size is its content, so without it the body refuses to shrink and the panel overflows.
 */
function DrawerBody({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="drawer-body"
			className={cn("min-h-0 flex-1 overflow-y-auto overscroll-contain px-6 pb-6", className)}
			// A form disables every control while it submits, and then nothing in here is focusable —
			// but the panel's overflow still has to be readable to the end. Same call as `ui/table`.
			// oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- The scroll region must stay keyboard-reachable when nothing inside it is.
			tabIndex={0}
			{...props}
		/>
	);
}

function DrawerFooter({ className, ...props }: React.ComponentProps<"div">) {
	return (
		// Bordered, unlike the header: body content scrolls under it.
		<div
			data-slot="drawer-footer"
			className={cn(
				"mt-auto flex shrink-0 flex-col-reverse gap-2 border-t px-6 py-4 sm:flex-row sm:justify-end",
				className,
			)}
			{...props}
		/>
	);
}

function DrawerTitle({ className, ...props }: DrawerPrimitive.Title.Props) {
	return (
		<DrawerPrimitive.Title
			data-slot="drawer-title"
			className={cn("text-base font-medium text-foreground", className)}
			{...props}
		/>
	);
}

function DrawerDescription({ className, ...props }: DrawerPrimitive.Description.Props) {
	return (
		<DrawerPrimitive.Description
			data-slot="drawer-description"
			className={cn("text-sm text-muted-foreground", className)}
			{...props}
		/>
	);
}

export {
	Drawer,
	DrawerBody,
	DrawerClose,
	DrawerContent,
	DrawerDescription,
	DrawerFooter,
	DrawerHeader,
	DrawerOverlay,
	DrawerPortal,
	DrawerSwipeHandle,
	DrawerTitle,
	DrawerTrigger,
};
