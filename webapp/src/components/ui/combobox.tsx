"use client";

import { Combobox as ComboboxPrimitive } from "@base-ui/react/combobox";
import { CheckIcon, SearchIcon } from "lucide-react";

import { cn } from "cn";
import type { AccessibleNameProps } from "@/components/ui/accessible-name";
import { InputGroup, InputGroupAddon } from "@/components/ui/input-group";

const Combobox = ComboboxPrimitive.Root;
const ComboboxCollection = ComboboxPrimitive.Collection;
const ComboboxPortal = ComboboxPrimitive.Portal;

const useComboboxFilter = ComboboxPrimitive.useFilter;

function ComboboxValue({ ...props }: ComboboxPrimitive.Value.Props) {
	return <ComboboxPrimitive.Value {...props} />;
}

function ComboboxIcon({ className, ...props }: ComboboxPrimitive.Icon.Props) {
	return (
		<ComboboxPrimitive.Icon
			data-slot="combobox-icon"
			className={cn("pointer-events-none shrink-0 text-muted-foreground", className)}
			{...props}
		/>
	);
}

function ComboboxTrigger({
	className,
	size = "default",
	children,
	...props
}: ComboboxPrimitive.Trigger.Props & {
	size?: "sm" | "default";
}) {
	return (
		<ComboboxPrimitive.Trigger
			data-slot="combobox-trigger"
			data-size={size}
			className={cn(
				"flex w-fit items-center justify-between gap-1.5 rounded-lg border border-input bg-transparent py-2 pr-2 pl-2.5 text-sm whitespace-nowrap transition-colors outline-none select-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 data-[placeholder]:text-muted-foreground data-[size=default]:h-8 data-[size=sm]:h-7 data-[size=sm]:rounded-[min(var(--radius-md),10px)] *:data-[slot=combobox-value]:line-clamp-1 *:data-[slot=combobox-value]:flex *:data-[slot=combobox-value]:items-center *:data-[slot=combobox-value]:gap-1.5 dark:bg-input/30 dark:hover:bg-input/50 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
				className,
			)}
			{...props}
		>
			{children}
		</ComboboxPrimitive.Trigger>
	);
}

function ComboboxContent({
	className,
	children,
	side = "bottom",
	sideOffset = 4,
	align = "center",
	alignOffset = 0,
	...props
}: ComboboxPrimitive.Popup.Props &
	Pick<ComboboxPrimitive.Positioner.Props, "align" | "alignOffset" | "side" | "sideOffset">) {
	return (
		<ComboboxPrimitive.Portal>
			<ComboboxPrimitive.Positioner
				side={side}
				sideOffset={sideOffset}
				align={align}
				alignOffset={alignOffset}
				className="isolate z-50"
			>
				<ComboboxPrimitive.Popup
					data-slot="combobox-content"
					className={cn(
						"relative isolate z-50 flex max-h-(--available-height) w-(--anchor-width) min-w-36 origin-(--transform-origin) flex-col overflow-hidden rounded-lg bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10 duration-100 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-[side=bottom]:slide-in-from-top-2 data-[side=inline-end]:slide-in-from-left-2 data-[side=inline-start]:slide-in-from-right-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2",
						className,
					)}
					{...props}
				>
					{children}
				</ComboboxPrimitive.Popup>
			</ComboboxPrimitive.Positioner>
		</ComboboxPrimitive.Portal>
	);
}

function ComboboxInput({ className, ...props }: ComboboxPrimitive.Input.Props) {
	return (
		<ComboboxPrimitive.Input
			data-slot="combobox-input"
			className={cn(
				"w-full text-sm outline-hidden disabled:cursor-not-allowed disabled:opacity-50",
				className,
			)}
			{...props}
		/>
	);
}

function ComboboxSearchInput({
	className,
	containerClassName,
	...props
}: ComboboxPrimitive.Input.Props & { containerClassName?: string }) {
	return (
		<div data-slot="combobox-search-input-wrapper" className={cn("p-1 pb-0", containerClassName)}>
			<InputGroup className="h-8! rounded-lg! border-input/30 bg-input/30 shadow-none! *:data-[slot=input-group-addon]:pl-2!">
				<ComboboxInput className={className} {...props} />
				<InputGroupAddon>
					<SearchIcon className="size-4 shrink-0 opacity-50" />
				</InputGroupAddon>
			</InputGroup>
		</div>
	);
}

/**
 * The listbox itself, which is why the accessible name is required here and not on `ComboboxContent`:
 * that popup is `role="presentation"` unless it holds the input, so a name on it lands on nothing.
 */
type ComboboxListProps = Omit<ComboboxPrimitive.List.Props, "aria-label" | "aria-labelledby"> &
	AccessibleNameProps;

function ComboboxList({ className, ...props }: ComboboxListProps) {
	return (
		<ComboboxPrimitive.List
			data-slot="combobox-list"
			className={cn(
				"max-h-72 scroll-py-1 overflow-x-hidden overflow-y-auto p-1 outline-none",
				className,
			)}
			{...props}
		/>
	);
}

function ComboboxEmpty({ className, ...props }: ComboboxPrimitive.Empty.Props) {
	return (
		<ComboboxPrimitive.Empty
			data-slot="combobox-empty"
			className={cn("py-6 text-center text-sm text-muted-foreground empty:hidden", className)}
			{...props}
		/>
	);
}

function ComboboxStatus({ className, ...props }: ComboboxPrimitive.Status.Props) {
	return (
		<ComboboxPrimitive.Status
			data-slot="combobox-status"
			className={cn("py-6 text-center text-sm text-muted-foreground empty:hidden", className)}
			{...props}
		/>
	);
}

function ComboboxGroup({ className, ...props }: ComboboxPrimitive.Group.Props) {
	return (
		<ComboboxPrimitive.Group
			data-slot="combobox-group"
			className={cn("scroll-my-1", className)}
			{...props}
		/>
	);
}

function ComboboxLabel({ className, ...props }: ComboboxPrimitive.GroupLabel.Props) {
	return (
		<ComboboxPrimitive.GroupLabel
			data-slot="combobox-label"
			className={cn("px-1.5 py-1 text-xs text-muted-foreground", className)}
			{...props}
		/>
	);
}

function ComboboxItem({ className, children, ...props }: ComboboxPrimitive.Item.Props) {
	return (
		<ComboboxPrimitive.Item
			data-slot="combobox-item"
			className={cn(
				"relative flex w-full cursor-default items-center gap-1.5 rounded-md py-1 pr-8 pl-1.5 text-sm outline-hidden select-none data-highlighted:bg-accent data-highlighted:text-accent-foreground data-highlighted:**:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
				className,
			)}
			{...props}
		>
			{children}
		</ComboboxPrimitive.Item>
	);
}

function ComboboxItemIndicator({ className, ...props }: ComboboxPrimitive.ItemIndicator.Props) {
	return (
		<ComboboxPrimitive.ItemIndicator
			data-slot="combobox-item-indicator"
			render={
				<span
					className={cn(
						"pointer-events-none absolute right-2 flex size-4 items-center justify-center",
						className,
					)}
				/>
			}
			{...props}
		>
			<CheckIcon className="pointer-events-none" />
		</ComboboxPrimitive.ItemIndicator>
	);
}

/**
 * Presentational, not `role="separator"`: `separator` is not an allowed child of `listbox`, so a
 * divider between items would make the list itself invalid. Base UI's part renders the same `<div>`
 * with `role="presentation"`, which is also the right answer for a purely decorative rule outside
 * the list.
 */
function ComboboxSeparator({ className, ...props }: ComboboxPrimitive.Separator.Props) {
	return (
		<ComboboxPrimitive.Separator
			data-slot="combobox-separator"
			className={cn("pointer-events-none -mx-1 my-1 h-px bg-border", className)}
			{...props}
		/>
	);
}

export {
	Combobox,
	ComboboxCollection,
	ComboboxContent,
	ComboboxEmpty,
	ComboboxGroup,
	ComboboxIcon,
	ComboboxInput,
	ComboboxItem,
	ComboboxItemIndicator,
	ComboboxLabel,
	ComboboxList,
	ComboboxPortal,
	ComboboxSearchInput,
	ComboboxSeparator,
	ComboboxStatus,
	ComboboxTrigger,
	ComboboxValue,
	useComboboxFilter,
};
