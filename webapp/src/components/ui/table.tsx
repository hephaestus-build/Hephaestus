import type * as React from "react";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add table` drops the following; re-apply it.
 *
 * `TableHead` and `TableCell` take `numeric`, which gives figures one advance width so digits line up
 * down a column and a changing value does not shift the ones beside it. Upstream leaves that to each
 * call site, which is how 39 cells came to spell it out by hand.
 */

function Table({
	className,
	containerClassName,
	...props
}: React.ComponentProps<"table"> & {
	containerClassName?: string;
}) {
	return (
		<div
			data-slot="table-container"
			className={cn("relative w-full overflow-x-auto", containerClassName)}
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

function TableHeader({ className, ...props }: React.ComponentProps<"thead">) {
	return <thead data-slot="table-header" className={cn("[&_tr]:border-b", className)} {...props} />;
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

function TableFooter({ className, ...props }: React.ComponentProps<"tfoot">) {
	return (
		<tfoot
			data-slot="table-footer"
			className={cn("bg-muted/50 border-t font-medium [&>tr]:last:border-b-0", className)}
			{...props}
		/>
	);
}

function TableRow({ className, ...props }: React.ComponentProps<"tr">) {
	return (
		<tr
			data-slot="table-row"
			className={cn(
				"hover:bg-muted/50 data-[state=selected]:bg-muted border-b transition-colors",
				className,
			)}
			{...props}
		/>
	);
}

/**
 * `numeric` marks a cell that holds a number. Figures then share one advance width, so digits line
 * up down the column and a changing value does not shift the ones beside it. Which numeral variant
 * that takes is the table's decision, not each call site's; alignment stays the caller's layout.
 */
type NumericCell = { numeric?: boolean };

function TableHead({ className, numeric, ...props }: React.ComponentProps<"th"> & NumericCell) {
	return (
		<th
			data-slot="table-head"
			className={cn(
				"text-foreground h-10 px-2 text-left align-middle font-medium whitespace-nowrap [&:has([role=checkbox])]:pr-0",
				numeric && "tabular-nums",
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
				numeric && "tabular-nums",
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
			className={cn("text-muted-foreground mt-4 text-sm", className)}
			{...props}
		/>
	);
}

export { Table, TableBody, TableCaption, TableCell, TableFooter, TableHead, TableHeader, TableRow };
