import { Link, useLocation } from "@tanstack/react-router";
import { Bug, ShieldCheck, SlidersHorizontal, Sparkles, UserRoundCog } from "lucide-react";

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
	// Changing the AI choice ends on the page the reader left, never on the setup page itself.
	const returnTo = useLocation().href;
	return (
		<>
			{isAppAdmin && (
				<>
					<SidebarMenu>
						<SidebarMenuItem>
							<SidebarMenuButton tooltip="Instance admin" render={<Link to="/admin" />}>
								<ShieldCheck />
								<span>Instance admin</span>
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
							tooltip="Your AI choice"
							render={
								<Link
									to="/w/$workspaceSlug/onboarding"
									params={{ workspaceSlug }}
									search={{ returnTo }}
								/>
							}
						>
							<SlidersHorizontal />
							<span>Your AI choice</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
				<SidebarMenuItem>
					<SidebarMenuButton tooltip="User settings" render={<Link to="/settings" />}>
						<UserRoundCog />
						<span>User settings</span>
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
						<span>Report issue</span>
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
						<span>Request a feature</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</>
	);
}
