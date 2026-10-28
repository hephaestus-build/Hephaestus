import { Link, useMatchRoute } from "@tanstack/react-router";
import { Building2, ChartNoAxesGantt, Users } from "lucide-react";

import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
} from "@/components/ui/sidebar";

export function NavWorkspace({
	workspaceSlug,
	practicesEnabled,
}: {
	workspaceSlug: string;
	practicesEnabled: boolean;
}) {
	const matchRoute = useMatchRoute();
	return (
		<SidebarGroup>
			<SidebarGroupLabel>Workspace</SidebarGroupLabel>
			<SidebarMenu aria-label="Workspace">
				{practicesEnabled && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip="Practices across the workspace"
							className="h-auto [&>span:last-child]:whitespace-normal"
							isActive={Boolean(
								matchRoute({ to: "/w/$workspaceSlug/practices-across-the-workspace", fuzzy: true }),
							)}
							render={
								<Link
									to="/w/$workspaceSlug/practices-across-the-workspace"
									params={{ workspaceSlug }}
								/>
							}
						>
							<ChartNoAxesGantt aria-hidden />
							<span>Practices across the workspace</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Workspace activity"
						isActive={Boolean(
							matchRoute({ to: "/w/$workspaceSlug/workspace-activity", fuzzy: true }),
						)}
						render={<Link to="/w/$workspaceSlug/workspace-activity" params={{ workspaceSlug }} />}
					>
						<Building2 aria-hidden />
						<span>Workspace activity</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Teams"
						isActive={Boolean(matchRoute({ to: "/w/$workspaceSlug/teams", fuzzy: true }))}
						render={<Link to="/w/$workspaceSlug/teams" params={{ workspaceSlug }} />}
					>
						<Users aria-hidden />
						<span>Teams</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</SidebarGroup>
	);
}
