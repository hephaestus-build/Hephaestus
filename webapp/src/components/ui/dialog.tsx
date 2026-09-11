"use client";

import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { XIcon } from "lucide-react";
import type * as React from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add dialog` drops the following; re-apply them.
 *
 * 1. `DialogContent` is height-bound and scrollable: upstream's popup is `fixed` with no
 *    `max-height`, and a fixed element taller than the viewport cannot be scrolled back into view
 *    (WCAG 2.2 SC 1.4.10).
 * 2. `DialogBody`, the opt-in scrollable middle.
 * 3. `DialogForm`, the `display: contents` form wrapper.
 */
function Dialog({ ...props }: DialogPrimitive.Root.Props) {
	return <DialogPrimitive.Root data-slot="dialog" {...props} />;
}

function DialogTrigger({ ...props }: DialogPrimitive.Trigger.Props) {
	return <DialogPrimitive.Trigger data-slot="dialog-trigger" {...props} />;
}

function DialogPortal({ ...props }: DialogPrimitive.Portal.Props) {
	return <DialogPrimitive.Portal data-slot="dialog-portal" {...props} />;
}

function DialogClose({ ...props }: DialogPrimitive.Close.Props) {
	return <DialogPrimitive.Close data-slot="dialog-close" {...props} />;
}

function DialogOverlay({ className, ...props }: DialogPrimitive.Backdrop.Props) {
	return (
		<DialogPrimitive.Backdrop
			data-slot="dialog-overlay"
			className={cn(
				"data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 bg-black/10 duration-100 supports-backdrop-filter:backdrop-blur-xs fixed inset-0 isolate z-50",
				className,
			)}
			{...props}
		/>
	);
}

function DialogContent({
	className,
	children,
	showCloseButton = true,
	...props
}: DialogPrimitive.Popup.Props & {
	showCloseButton?: boolean;
}) {
	return (
		<DialogPortal>
			<DialogOverlay />
			<DialogPrimitive.Popup
				data-slot="dialog-content"
				className={cn(
					"bg-background data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95 ring-foreground/10 grid max-w-[calc(100%-2rem)] gap-4 rounded-xl p-4 text-sm ring-1 duration-100 sm:max-w-sm fixed top-1/2 left-1/2 z-50 w-full -translate-x-1/2 -translate-y-1/2 outline-none",
					// `svh`, not `dvh`: mobile browser chrome collapses while scrolling, and `dvh` would
					// resize the dialog under the user's finger.
					"max-h-[calc(100svh-2rem)] overflow-y-auto overscroll-contain",
					"has-data-[slot=dialog-body]:flex has-data-[slot=dialog-body]:flex-col has-data-[slot=dialog-body]:overflow-hidden",
					className,
				)}
				{...props}
			>
				{children}
				{showCloseButton && (
					<DialogPrimitive.Close
						data-slot="dialog-close"
						render={<Button variant="ghost" className="absolute top-2 right-2" size="icon-sm" />}
					>
						<XIcon />
						<span className="sr-only">Close</span>
					</DialogPrimitive.Close>
				)}
			</DialogPrimitive.Popup>
		</DialogPortal>
	);
}

function DialogHeader({ className, ...props }: React.ComponentProps<"div">) {
	return (
		// `shrink-0`: `DialogBody` is `flex-1` off a zero basis, so the header is what would be squashed.
		<div
			data-slot="dialog-header"
			className={cn("gap-2 flex shrink-0 flex-col", className)}
			{...props}
		/>
	);
}

/**
 * The scrollable middle of a tall dialog; its presence switches {@link DialogContent} to "only the
 * body scrolls". `min-h-0` is load-bearing — a flex item's automatic minimum size is its content,
 * so without it the body refuses to shrink and the popup overflows again.
 */
function DialogBody({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="dialog-body"
			className={cn("-mx-4 min-h-0 flex-1 overflow-y-auto overscroll-contain px-4", className)}
			{...props}
		/>
	);
}

/**
 * `display: contents` keeps header, body and footer as {@link DialogContent}'s own flex children; a
 * form with a box of its own would defeat the pinned-header/scrolling-body column. `noValidate`
 * because these forms report their own errors: the browser's bubble announces nothing.
 */
function DialogForm({ className, ...props }: React.ComponentProps<"form">) {
	return <form className={cn("contents", className)} noValidate {...props} />;
}

function DialogFooter({
	className,
	showCloseButton = false,
	children,
	...props
}: React.ComponentProps<"div"> & {
	showCloseButton?: boolean;
}) {
	return (
		<div
			data-slot="dialog-footer"
			className={cn(
				"bg-muted/50 -mx-4 -mb-4 rounded-b-xl border-t p-4 flex shrink-0 flex-col-reverse gap-2 sm:flex-row sm:justify-end",
				className,
			)}
			{...props}
		>
			{children}
			{showCloseButton && (
				<DialogPrimitive.Close render={<Button variant="outline" />}>Close</DialogPrimitive.Close>
			)}
		</div>
	);
}

function DialogTitle({ className, ...props }: DialogPrimitive.Title.Props) {
	return (
		<DialogPrimitive.Title
			data-slot="dialog-title"
			className={cn("text-base leading-none font-medium", className)}
			{...props}
		/>
	);
}

function DialogDescription({ className, ...props }: DialogPrimitive.Description.Props) {
	return (
		<DialogPrimitive.Description
			data-slot="dialog-description"
			className={cn(
				"text-muted-foreground *:[a]:hover:text-foreground text-sm *:[a]:underline *:[a]:underline-offset-3",
				className,
			)}
			{...props}
		/>
	);
}

export {
	Dialog,
	DialogBody,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogOverlay,
	DialogPortal,
	DialogTitle,
	DialogTrigger,
};
