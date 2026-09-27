import { Link, useMatchRoute } from "@tanstack/react-router";
import { Activity, Building2, Compass, Radar, Users } from "lucide-react";

import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
} from "@/components/ui/sidebar";

export function NavDashboards({
	workspaceSlug,
	practicesEnabled,
}: {
	workspaceSlug: string;
	practicesEnabled: boolean;
}) {
	const matchRoute = useMatchRoute();
	const onActivity = Boolean(matchRoute({ to: "/w/$workspaceSlug/activity", fuzzy: true }));
	const onPracticeProfile = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/practice-profile", fuzzy: true }),
	);
	const onWorkspaceActivity = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/workspace-activity", fuzzy: true }),
	);
	const onTeams = Boolean(matchRoute({ to: "/w/$workspaceSlug/teams", fuzzy: true }));
	const onReviews = Boolean(matchRoute({ to: "/w/$workspaceSlug/reviews", fuzzy: true }));

	return (
		<SidebarGroup>
			<SidebarGroupLabel>Dashboards</SidebarGroupLabel>
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Activity"
						isActive={onActivity}
						render={<Link to="/w/$workspaceSlug/activity" params={{ workspaceSlug }} />}
					>
						<Activity />
						<span>Activity</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				{practicesEnabled && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip="Practice profile"
							isActive={onPracticeProfile}
							render={<Link to="/w/$workspaceSlug/practice-profile" params={{ workspaceSlug }} />}
						>
							<Compass />
							<span>Practice profile</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Workspace activity"
						isActive={onWorkspaceActivity}
						render={<Link to="/w/$workspaceSlug/workspace-activity" params={{ workspaceSlug }} />}
					>
						<Building2 />
						<span>Workspace activity</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				{practicesEnabled && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip="Review activity"
							isActive={onReviews}
							render={<Link to="/w/$workspaceSlug/reviews" params={{ workspaceSlug }} />}
						>
							<Radar />
							<span>Review activity</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Teams"
						isActive={onTeams}
						render={<Link to="/w/$workspaceSlug/teams" params={{ workspaceSlug }} />}
					>
						<Users />
						<span>Teams</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</SidebarGroup>
	);
}
