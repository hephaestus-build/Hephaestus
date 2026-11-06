import { Link, useLocation } from "@tanstack/react-router";
import { Inbox, ShieldCheck, SlidersHorizontal, UserRoundCog } from "lucide-react";
import { hasText } from "@/lib/text";

import {
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarSeparator,
} from "@/components/ui/sidebar";

interface NavFooterProps {
	isAppAdmin?: boolean;
	/** Product feedback that waits in the instance console, where only this row leads an admin. */
	waitingReports?: number;
	workspaceSlug?: string;
}

function waitingLabel(count: number): string {
	return count === 1 ? "1 report waiting" : `${count} reports waiting`;
}

export function NavFooter({
	isAppAdmin = false,
	waitingReports = 0,
	workspaceSlug,
}: NavFooterProps) {
	// Changing the AI choice ends on the page the reader left, never on the setup page itself.
	const returnTo = useLocation().href;
	return (
		<>
			{isAppAdmin && (
				<>
					<SidebarMenu>
						{waitingReports > 0 && (
							<SidebarMenuItem>
								<SidebarMenuButton
									tooltip={waitingLabel(waitingReports)}
									render={<Link to="/admin/feedback" search={{ chromeWorkspace: workspaceSlug }} />}
								>
									<Inbox />
									<span>{waitingLabel(waitingReports)}</span>
								</SidebarMenuButton>
							</SidebarMenuItem>
						)}
						<SidebarMenuItem>
							<SidebarMenuButton
								tooltip="Instance administration"
								render={<Link to="/admin" search={{ chromeWorkspace: workspaceSlug }} />}
							>
								<ShieldCheck />
								<span>Instance administration</span>
							</SidebarMenuButton>
						</SidebarMenuItem>
					</SidebarMenu>
					<SidebarSeparator />
				</>
			)}
			<SidebarMenu>
				{hasText(workspaceSlug) && (
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
					<SidebarMenuButton
						tooltip="User settings"
						render={<Link to="/settings" search={{ chromeWorkspace: workspaceSlug }} />}
					>
						<UserRoundCog />
						<span>User settings</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</>
	);
}
