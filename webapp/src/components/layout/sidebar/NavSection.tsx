import { ChevronRight } from "lucide-react";
import { type ReactElement, type ReactNode, useState } from "react";

import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuLabel,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
	SidebarMenuBadge,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarMenuSub,
	useSidebar,
} from "@/components/ui/sidebar";

/**
 * Forced open when the reader navigates into the section, freely collapsible otherwise. Adjusted
 * during render, not in an effect, so arriving on a page never paints its section collapsed first.
 */
function useSectionOpen(onSection: boolean) {
	const [open, setOpen] = useState(onSection);
	const [wasOnSection, setWasOnSection] = useState(onSection);

	if (onSection !== wasOnSection) {
		setWasOnSection(onSection);
		if (onSection) {
			setOpen(true);
		}
	}

	return [open, setOpen] as const;
}

interface NavSectionBaseProps {
	label: string;
	icon: ReactNode;
	/** The current page is in the section. */
	active: boolean;
	/** Shown on the section while its entries are out of sight. */
	badge?: { count: number; phrase: string };
	children: ReactNode;
}

/**
 * In the icon-only sidebar nothing can unfold, so the section is a link to a landing page that
 * reaches its entries, or, where no page does, a menu of the entries.
 */
export type NavSectionProps = NavSectionBaseProps &
	(
		| { landingLink: ReactElement; menu?: never }
		| {
				/** The section's entries as `DropdownMenuItem`s. */
				menu: ReactNode;
				landingLink?: never;
		  }
	);

/**
 * A collapsible sidebar entry, composed from the `Collapsible` and `SidebarMenu*` primitives as
 * shadcn's collapsible sidebar does. The kit has no section component of its own.
 */
export function NavSection({
	label,
	icon,
	active,
	badge,
	landingLink,
	menu,
	children,
}: NavSectionProps) {
	const [open, setOpen] = useSectionOpen(active);
	// On a phone the sidebar is a sheet with full labels, never icon-only.
	const { isMobile, state } = useSidebar();
	const collapsed = !isMobile && state === "collapsed";
	const shown = collapsed || !open ? badge : undefined;
	const name = (
		<span>
			{label}
			{shown && <span className="sr-only"> ({shown.phrase})</span>}
		</span>
	);
	// `SidebarMenuBadge` hides itself in the icon-only sidebar, so the tooltip carries the count.
	const tooltip = shown ? `${label} (${shown.phrase})` : label;
	let button: ReactNode;
	if (!collapsed) {
		button = (
			<CollapsibleTrigger
				render={<SidebarMenuButton tooltip={tooltip} isActive={!open && active} />}
			>
				{icon}
				{name}
				<ChevronRight
					className="ml-auto transition-transform group-aria-expanded/menu-button:rotate-90"
					aria-hidden
				/>
			</CollapsibleTrigger>
		);
	} else if (landingLink === undefined) {
		button = (
			<DropdownMenu>
				<DropdownMenuTrigger render={<SidebarMenuButton tooltip={tooltip} isActive={active} />}>
					{icon}
					{name}
				</DropdownMenuTrigger>
				<DropdownMenuContent side="right" align="start" className="min-w-48">
					<DropdownMenuGroup>
						<DropdownMenuLabel className="text-xs text-muted-foreground">{label}</DropdownMenuLabel>
						{menu}
					</DropdownMenuGroup>
				</DropdownMenuContent>
			</DropdownMenu>
		);
	} else {
		button = (
			<SidebarMenuButton tooltip={tooltip} isActive={active} render={landingLink}>
				{icon}
				{name}
			</SidebarMenuButton>
		);
	}
	return (
		<Collapsible open={open} onOpenChange={setOpen} render={<SidebarMenuItem />}>
			{button}
			{shown && (
				// The button's name carries the count; `right-7` clears the chevron.
				<SidebarMenuBadge aria-hidden className="right-7">
					{shown.count}
				</SidebarMenuBadge>
			)}
			<CollapsibleContent>
				{/* Named, so a screen reader jumping by list hears which section it landed in. */}
				<SidebarMenuSub aria-label={label}>{children}</SidebarMenuSub>
			</CollapsibleContent>
		</Collapsible>
	);
}
