import { Link, useMatchRoute } from "@tanstack/react-router";
import { Activity, Building2, ChartNoAxesGantt, Compass, UserRound, Users } from "lucide-react";

import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarMenuSubButton,
	SidebarMenuSubItem,
	useSidebar,
} from "@/components/ui/sidebar";

import { NavSection, useSectionOpen } from "./NavSection";

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
	const onAcrossTheWorkspace = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/practices-across-the-workspace", fuzzy: true }),
	);
	const onWorkspaceActivity = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/workspace-activity", fuzzy: true }),
	);
	const onTeams = Boolean(matchRoute({ to: "/w/$workspaceSlug/teams", fuzzy: true }));
	const onPracticePages = onPracticeProfile || onAcrossTheWorkspace;
	const [practicesOpen, setPracticesOpen] = useSectionOpen(onPracticePages);
	const { isMobile, state: sidebarState } = useSidebar();

	return (
		<SidebarGroup>
			<SidebarGroupLabel>Dashboards</SidebarGroupLabel>
			<SidebarMenu>
				{practicesEnabled && (
					<NavSection
						label="Practice profile"
						icon={<Compass />}
						active={onPracticePages}
						open={practicesOpen}
						onOpenChange={setPracticesOpen}
						collapsed={!isMobile && sidebarState === "collapsed"}
						landingLink={
							<Link
								to="/w/$workspaceSlug/practice-profile"
								params={{ workspaceSlug }}
								aria-current={onPracticeProfile ? "page" : undefined}
							/>
						}
					>
						<SidebarMenuSubItem>
							<SidebarMenuSubButton
								isActive={onPracticeProfile}
								render={<Link to="/w/$workspaceSlug/practice-profile" params={{ workspaceSlug }} />}
							>
								<UserRound aria-hidden />
								<span>Your profile</span>
							</SidebarMenuSubButton>
						</SidebarMenuSubItem>
						<SidebarMenuSubItem>
							<SidebarMenuSubButton
								isActive={onAcrossTheWorkspace}
								render={
									<Link
										to="/w/$workspaceSlug/practices-across-the-workspace"
										params={{ workspaceSlug }}
									/>
								}
							>
								<ChartNoAxesGantt aria-hidden />
								<span>Across the workspace</span>
							</SidebarMenuSubButton>
						</SidebarMenuSubItem>
					</NavSection>
				)}
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
