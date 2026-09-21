import { Link, useMatchRoute } from "@tanstack/react-router";
import { Compass, Radar, Sparkles, Trophy, User, Users } from "lucide-react";

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
	achievementsEnabled,
	leaderboardEnabled,
}: {
	username: string;
	workspaceSlug: string;
	achievementsEnabled: boolean;
	leaderboardEnabled: boolean;
}) {
	const matchRoute = useMatchRoute();
	const onPracticeProfile = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/practice-profile", fuzzy: true }),
	);
	const onProfile = Boolean(matchRoute({ to: "/w/$workspaceSlug/user/$username", fuzzy: true }));
	const onAchievements = Boolean(matchRoute({ to: "/w/$workspaceSlug/achievements", fuzzy: true }));
	const onLeaderboard = Boolean(matchRoute({ to: "/w/$workspaceSlug", fuzzy: false }));
	const onTeams = Boolean(matchRoute({ to: "/w/$workspaceSlug/teams", fuzzy: true }));
	const onReviews = Boolean(matchRoute({ to: "/w/$workspaceSlug/reviews", fuzzy: true }));

	return (
		<SidebarGroup>
			<SidebarGroupLabel>Dashboards</SidebarGroupLabel>
			<SidebarMenu>
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
				{achievementsEnabled && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip="Achievements"
							isActive={onAchievements}
							render={<Link to="/w/$workspaceSlug/achievements" params={{ workspaceSlug }} />}
						>
							<Sparkles />
							<span>Achievements</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
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
				{/* Deliberately not feature-gated: with practices off the page says so, which is the answer
				    a developer wondering why nothing was said came for. */}
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
