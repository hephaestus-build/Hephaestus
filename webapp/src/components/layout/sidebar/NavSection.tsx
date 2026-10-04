import { ChevronRight } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	SidebarMenuBadge,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarMenuSub,
	useSidebar,
} from "@/components/ui/sidebar";

import { useSectionOpen } from "./use-section-open";

export interface NavSectionProps {
	label: string;
	icon: ReactNode;
	/** The current page is in the section. */
	active: boolean;
	/** Shown on the section while its entries are out of sight. */
	badge?: { count: number; phrase: string };
	/**
	 * In the icon-only sidebar nothing can unfold, so the section is a link to a landing page that
	 * reaches its entries.
	 */
	landingLink: ReactElement;
	children: ReactNode;
}

/**
 * A collapsible sidebar entry, composed from the `Collapsible` and `SidebarMenu*` primitives as
 * shadcn's collapsible sidebar does. The kit has no section component of its own.
 */
export function NavSection({ label, icon, active, badge, landingLink, children }: NavSectionProps) {
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
	const button = collapsed ? (
		<SidebarMenuButton tooltip={tooltip} isActive={active} render={landingLink}>
			{icon}
			{name}
		</SidebarMenuButton>
	) : (
		<CollapsibleTrigger render={<SidebarMenuButton tooltip={tooltip} isActive={!open && active} />}>
			{icon}
			{name}
			<ChevronRight
				className="ml-auto transition-transform group-aria-expanded/menu-button:rotate-90"
				aria-hidden
			/>
		</CollapsibleTrigger>
	);
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
