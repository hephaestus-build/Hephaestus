"use client";

import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { XIcon } from "lucide-react";
import type * as React from "react";
import { useRef } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add dialog` drops the following; re-apply them.
 *
 * 1. `DialogContent` is height-bound and scrollable: upstream's popup is `fixed` with no
 *    `max-height`, and a fixed element taller than the viewport cannot be scrolled back into view
 *    (WCAG 2.2 SC 1.4.10).
 * 2. `DialogBody`, the opt-in scrollable middle, keyboard-focusable for the same reason as
 *    `DrawerBody`: a submitting form disables everything inside it.
 * 3. `DialogForm`, the `display: contents` form wrapper.
 * 4. `DialogContent` opens on the first control inside the body. The body is keyboard-focusable
 *    (2), so it is the first tab stop, and Base UI's default would land focus on an unnamed box
 *    instead of the field the reader came to fill in (WCAG 2.2 SC 2.4.3). A body with nothing to
 *    focus keeps the default, and a caller's own `initialFocus` wins.
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
				"fixed inset-0 isolate z-50 bg-black/10 duration-100 supports-backdrop-filter:backdrop-blur-xs data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0",
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
}: Omit<DialogPrimitive.Popup.Props, "ref"> & {
	showCloseButton?: boolean;
}) {
	const popup = useRef<HTMLDivElement>(null);
	return (
		<DialogPortal>
			<DialogOverlay />
			<DialogPrimitive.Popup
				ref={popup}
				initialFocus={(interaction) =>
					// Touch keeps the default so the on-screen keyboard stays shut.
					interaction === "touch"
						? true
						: (popup.current?.querySelector<HTMLElement>(
								"[data-slot=dialog-body] :is(input, textarea, select, button, [href], [role=checkbox], [role=radio], [role=switch], [role=combobox]):not([disabled])",
							) ?? true)
				}
				data-slot="dialog-content"
				className={cn(
					"fixed top-1/2 left-1/2 z-50 grid w-full max-w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 gap-4 rounded-xl bg-background p-4 text-sm ring-1 ring-foreground/10 duration-100 outline-none sm:max-w-sm data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95",
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
			className={cn("flex shrink-0 flex-col gap-2", className)}
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
			// oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- The scroll region must stay keyboard-reachable when nothing inside it is.
			tabIndex={0}
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
				"-mx-4 -mb-4 flex shrink-0 flex-col-reverse gap-2 rounded-b-xl border-t bg-muted/50 p-4 sm:flex-row sm:justify-end",
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
				"text-sm text-muted-foreground *:[a]:underline *:[a]:underline-offset-3 *:[a]:hover:text-foreground",
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
