import { ChevronRight } from "lucide-react";
import { type ReactElement, type ReactNode, useState } from "react";

import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
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

export interface NavSectionProps {
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
	landingLink: ReactElement;
	children: ReactNode;
}

/**
 * A sidebar entry that opens into sub entries: a trigger while the sidebar is expanded, and a link to
 * the section's landing page while it is down to icons, where nothing can unfold.
 */
export function NavSection({
	label,
	icon,
	active,
	open,
	onOpenChange,
	badge,
	landingLink,
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
	return (
		<Collapsible open={open} onOpenChange={onOpenChange} render={<SidebarMenuItem />}>
			{collapsed ? (
				<SidebarMenuButton tooltip={tooltip} isActive={active} render={landingLink}>
					{icon}
					{name}
				</SidebarMenuButton>
			) : (
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
			)}
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
