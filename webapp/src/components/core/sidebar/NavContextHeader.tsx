import { ChevronLeft } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import {
	SidebarGroup,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarSeparator,
} from "@/components/ui/sidebar";

import { rendersContent } from "@/lib/react-node";

export interface NavContextHeaderProps {
	title: string;
	backLink: ReactElement;
	children?: ReactNode;
}

export function NavContextHeader({ title, backLink, children }: NavContextHeaderProps) {
	return (
		<>
			<SidebarGroup className="pb-0">
				<SidebarMenu>
					<SidebarMenuItem>
						<SidebarMenuButton render={backLink}>
							<ChevronLeft />
							<span>{title}</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				</SidebarMenu>
			</SidebarGroup>
			{rendersContent(children) && (
				<SidebarSeparator className="data-[orientation=horizontal]:w-auto" />
			)}
			{children}
		</>
	);
}
