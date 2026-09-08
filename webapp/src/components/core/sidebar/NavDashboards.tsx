import { Link, useMatchRoute } from "@tanstack/react-router";
import { Radar, Trophy, User, Users } from "lucide-react";

import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
} from "@/components/ui/sidebar";

export function NavDashboards({
	username,
	workspaceSlug,
	leaderboardEnabled,
	practicesEnabled,
}: {
	username: string;
	workspaceSlug: string;
	leaderboardEnabled: boolean;
	practicesEnabled: boolean;
}) {
	const matchRoute = useMatchRoute();
	const onProfile = Boolean(matchRoute({ to: "/w/$workspaceSlug/user/$username", fuzzy: true }));
	const onLeaderboard = Boolean(matchRoute({ to: "/w/$workspaceSlug", fuzzy: false }));
	const onTeams = Boolean(matchRoute({ to: "/w/$workspaceSlug/teams", fuzzy: true }));
	const onReviews = Boolean(matchRoute({ to: "/w/$workspaceSlug/reviews", fuzzy: true }));

	return (
		<SidebarGroup>
			<SidebarGroupLabel>Dashboards</SidebarGroupLabel>
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Profile"
						isActive={onProfile}
						render={
							<Link to="/w/$workspaceSlug/user/$username" params={{ username, workspaceSlug }} />
						}
					>
						<User />
						<span>Profile</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				{leaderboardEnabled && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip="Leaderboard"
							isActive={onLeaderboard}
							render={<Link to="/w/$workspaceSlug" params={{ workspaceSlug }} />}
						>
							<Trophy />
							<span>Leaderboard</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
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
