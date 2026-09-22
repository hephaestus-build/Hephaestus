import { Link } from "@tanstack/react-router";
import { ShieldCheck, UserRoundCog } from "lucide-react";

import {
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarSeparator,
} from "@/components/ui/sidebar";

interface NavFooterProps {
	isAppAdmin?: boolean;
}

export function NavFooter({ isAppAdmin = false }: NavFooterProps) {
	return (
		<>
			{isAppAdmin && (
				<>
					<SidebarMenu>
						<SidebarMenuItem>
							<SidebarMenuButton tooltip="Instance admin" render={<Link to="/admin" />}>
								<ShieldCheck />
								<span>Instance&nbsp;admin</span>
							</SidebarMenuButton>
						</SidebarMenuItem>
					</SidebarMenu>
					<SidebarSeparator />
				</>
			)}
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton tooltip="User settings" render={<Link to="/settings" />}>
						<UserRoundCog />
						<span>User&nbsp;settings</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</>
	);
}
