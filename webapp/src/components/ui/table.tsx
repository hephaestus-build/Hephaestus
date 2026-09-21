import type * as React from "react";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add table` drops the following; re-apply them.
 *
 * 1. `TableHead` and `TableCell` take `numeric`.
 * 2. `Table` takes `bordered`.
 * 3. `TableRow` takes `variant`: `static` for a total or detail row that is not a hover target,
 *    `highlighted` for the reader's own row.
 * 4. `TableHeader` and `TableFooter` take `sticky`, pinned over the scrolling rows on an opaque
 *    surface.
 */
function Table({
	className,
	containerClassName,
	bordered = false,
	...props
}: React.ComponentProps<"table"> & {
	containerClassName?: string;
	/** Draws the rounded edge a data table standing on its own gets; off for a table inside a card. */
	bordered?: boolean;
}) {
	return (
		<div
			data-slot="table-container"
			className={cn(
				"relative w-full overflow-x-auto",
				bordered && "rounded-md border",
				containerClassName,
			)}
			// oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- A scrollable region has to be keyboard-reachable; axe's `scrollable-region-focusable` fails without it, and the Storybook a11y addon runs axe at error.
			tabIndex={0}
		>
			<table
				data-slot="table"
				className={cn("w-full caption-bottom text-sm", className)}
				{...props}
			/>
		</div>
	);
}

function TableHeader({
	className,
	sticky = false,
	...props
}: React.ComponentProps<"thead"> & { sticky?: boolean }) {
	return (
		<thead
			data-slot="table-header"
			className={cn("[&_tr]:border-b", sticky && "sticky top-0 z-10 bg-card", className)}
			{...props}
		/>
	);
}

function TableBody({ className, ...props }: React.ComponentProps<"tbody">) {
	return (
		<tbody
			data-slot="table-body"
			className={cn("[&_tr:last-child]:border-0", className)}
			{...props}
		/>
	);
}

function TableFooter({
	className,
	sticky = false,
	...props
}: React.ComponentProps<"tfoot"> & { sticky?: boolean }) {
	return (
		<tfoot
			data-slot="table-footer"
			className={cn(
				"border-t bg-muted/50 font-medium [&>tr]:last:border-b-0",
				sticky && "sticky bottom-0 z-10 border-t-2 bg-muted",
				className,
			)}
			{...props}
		/>
	);
}

function TableRow({
	className,
	variant = "default",
	...props
}: React.ComponentProps<"tr"> & { variant?: "default" | "static" | "highlighted" }) {
	return (
		<tr
			data-slot="table-row"
			data-variant={variant}
			className={cn(
				"border-b transition-colors hover:bg-muted/50 data-[state=selected]:bg-muted",
				variant === "static" && "hover:bg-transparent",
				variant === "highlighted" && "bg-accent dark:bg-accent/30 dark:hover:bg-accent/50",
				className,
			)}
			{...props}
		/>
	);
}

/** Tabular figures, so a column lines up and a changing value does not shift its neighbours; alignment stays the caller's layout. */
interface NumericCell {
	numeric?: boolean;
}

function TableHead({ className, numeric, ...props }: React.ComponentProps<"th"> & NumericCell) {
	return (
		<th
			data-slot="table-head"
			className={cn(
				"h-10 px-2 text-left align-middle font-medium whitespace-nowrap text-foreground [&:has([role=checkbox])]:pr-0",
				numeric === true && "tabular-nums",
				className,
			)}
			{...props}
		/>
	);
}

function TableCell({ className, numeric, ...props }: React.ComponentProps<"td"> & NumericCell) {
	return (
		<td
			data-slot="table-cell"
			className={cn(
				"p-2 align-middle whitespace-nowrap [&:has([role=checkbox])]:pr-0",
				numeric === true && "tabular-nums",
				className,
			)}
			{...props}
		/>
	);
}

function TableCaption({ className, ...props }: React.ComponentProps<"caption">) {
	return (
		<caption
			data-slot="table-caption"
			className={cn("mt-4 text-sm text-muted-foreground", className)}
			{...props}
		/>
	);
}

export { Table, TableBody, TableCaption, TableCell, TableFooter, TableHead, TableHeader, TableRow };
