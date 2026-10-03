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
 * Open state for a nav section, forced open when the user navigates into it and freely collapsible
 * the rest of the time. Adjusted during render, not in an effect, so arriving at a page in it
 * never paints the section collapsed first.
 */
export function useSectionOpen(onSection: boolean) {
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
	active: boolean;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	/**
	 * A count owed somewhere in the section, shown on the section itself while its entries are out of
	 * sight — closed, or the sidebar down to icons — so it is seen however the sidebar is folded.
	 */
	badge?: { count: number; phrase: string };
	children: ReactNode;
}

/**
 * What the section offers while the sidebar is down to icons: a link to its landing page, which
 * reaches its other entries, or, where the landing page links nowhere else, its entries as a menu.
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
 * A sidebar entry that opens into sub entries: a trigger while the sidebar is expanded, and while it
 * is down to icons, where nothing can unfold, a link to the section's landing page or a menu of its
 * entries beside the icon.
 */
export function NavSection({
	label,
	icon,
	active,
	open,
	onOpenChange,
	badge,
	landingLink,
	menu,
	children,
}: NavSectionProps) {
	// Down to icons on a wide screen; on a phone the sidebar is a sheet with its full labels.
	const { isMobile, state } = useSidebar();
	const collapsed = !isMobile && state === "collapsed";
	const shown = collapsed || !open ? badge : undefined;
	const name = (
		<span>
			{label}
			{shown && <span className="sr-only"> ({shown.phrase})</span>}
		</span>
	);
	// The badge hides itself in the icon-only sidebar, where the tooltip says the count instead.
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
		<Collapsible open={open} onOpenChange={onOpenChange} render={<SidebarMenuItem />}>
			{button}
			{shown && (
				// The button names the count for a screen reader; this is its picture, clear of the chevron.
				<SidebarMenuBadge aria-hidden className="right-7">
					{shown.count}
				</SidebarMenuBadge>
			)}
			<CollapsibleContent>
				{/* The list carries the section's name: a screen reader jumping by list otherwise
				    announces "list, 3 items" with nothing saying which section it landed in. */}
				<SidebarMenuSub aria-label={label}>{children}</SidebarMenuSub>
			</CollapsibleContent>
		</Collapsible>
	);
}
