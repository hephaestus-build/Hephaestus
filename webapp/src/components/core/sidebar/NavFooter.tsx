import { Link } from "@tanstack/react-router";
import { Bug, ShieldCheck, Sparkles, UserRoundCog } from "lucide-react";

import {
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarSeparator,
} from "@/components/ui/sidebar";

interface NavFooterProps {
	isAppAdmin?: boolean;
	workspaceSlug?: string;
}

export function NavFooter({ isAppAdmin = false, workspaceSlug }: NavFooterProps) {
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
				{workspaceSlug && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip="Workspace preferences"
							render={<Link to="/w/$workspaceSlug/onboarding" params={{ workspaceSlug }} />}
						>
							<ShieldCheck />
							<span>Workspace preferences</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
				<SidebarMenuItem>
					<SidebarMenuButton tooltip="User settings" render={<Link to="/settings" />}>
						<UserRoundCog />
						<span>User&nbsp;settings</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
			<SidebarSeparator />
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Report issue"
						render={<a href="https://github.com/hephaestus-build/Hephaestus/issues/new/choose" />}
					>
						<Bug />
						<span>Report&nbsp;issue</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Request a feature"
						className="text-provider-upsell-foreground hover:text-provider-upsell-foreground hover:bg-provider-upsell-foreground/10 dark:hover:bg-provider-upsell-foreground/10"
						render={
							<a href="https://github.com/hephaestus-build/Hephaestus/discussions/new/choose" />
						}
					>
						<Sparkles />
						<span>Request&nbsp;a&nbsp;feature</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</>
	);
}
