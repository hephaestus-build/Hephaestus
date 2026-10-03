import { Link, useMatchRoute } from "@tanstack/react-router";
import { Activity, Building2, ChartNoAxesGantt, Compass, UserRound, Users } from "lucide-react";

import { DropdownMenuItem } from "@/components/ui/dropdown-menu";
import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarMenuSubButton,
	SidebarMenuSubItem,
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
	// The profile links nowhere else, so down to icons the section offers both pages as a menu.
	const practicePages = [
		{
			to: "/w/$workspaceSlug/practice-profile",
			label: "Your profile",
			icon: UserRound,
			active: onPracticeProfile,
		},
		{
			to: "/w/$workspaceSlug/practices-across-the-workspace",
			label: "Across the workspace",
			icon: ChartNoAxesGantt,
			active: onAcrossTheWorkspace,
		},
	] as const;

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
						menu={practicePages.map((page) => (
							<DropdownMenuItem
								key={page.to}
								render={
									<Link
										to={page.to}
										params={{ workspaceSlug }}
										aria-current={page.active ? "page" : undefined}
									/>
								}
							>
								<page.icon aria-hidden />
								{page.label}
							</DropdownMenuItem>
						))}
					>
						{practicePages.map((page) => (
							<SidebarMenuSubItem key={page.to}>
								<SidebarMenuSubButton
									isActive={page.active}
									render={<Link to={page.to} params={{ workspaceSlug }} />}
								>
									<page.icon aria-hidden />
									<span>{page.label}</span>
								</SidebarMenuSubButton>
							</SidebarMenuSubItem>
						))}
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
