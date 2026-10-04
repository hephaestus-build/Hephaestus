import { Link, useMatchRoute } from "@tanstack/react-router";
import { Activity, Building2, ChartNoAxesGantt, ChevronRight, Compass, Users } from "lucide-react";

import { ACROSS_THE_WORKSPACE } from "@/components/practices-across-the-workspace/across-workspace-copy";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuAction,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarMenuSub,
	SidebarMenuSubButton,
	SidebarMenuSubItem,
	useSidebar,
} from "@/components/ui/sidebar";

import { useSectionOpen } from "./NavSection";

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
	const [practicesOpen, setPracticesOpen] = useSectionOpen(onAcrossTheWorkspace);
	// On a phone the sidebar is a sheet with full labels, never icon-only.
	const { isMobile, state } = useSidebar();
	const iconOnly = !isMobile && state === "collapsed";
	const acrossLink = (
		<Link to="/w/$workspaceSlug/practices-across-the-workspace" params={{ workspaceSlug }} />
	);

	return (
		<SidebarGroup>
			<SidebarGroupLabel>Dashboards</SidebarGroupLabel>
			<SidebarMenu>
				{/* The profile is the parent's own link, so one press reaches it from anywhere, and the
				    chevron beside it, not the label, discloses the workspace view. */}
				{practicesEnabled && (
					<Collapsible
						open={practicesOpen}
						onOpenChange={setPracticesOpen}
						render={<SidebarMenuItem />}
					>
						<SidebarMenuButton
							tooltip="Practice profile"
							isActive={onPracticeProfile || (onAcrossTheWorkspace && !practicesOpen)}
							render={<Link to="/w/$workspaceSlug/practice-profile" params={{ workspaceSlug }} />}
						>
							<Compass />
							<span>Practice profile</span>
						</SidebarMenuButton>
						<CollapsibleTrigger
							render={
								<SidebarMenuAction
									aria-label="Practice profile pages"
									className="aria-expanded:rotate-90"
								/>
							}
						>
							<ChevronRight aria-hidden />
						</CollapsibleTrigger>
						<CollapsibleContent>
							<SidebarMenuSub aria-label="Practice profile">
								<SidebarMenuSubItem>
									<SidebarMenuSubButton isActive={onAcrossTheWorkspace} render={acrossLink}>
										<ChartNoAxesGantt aria-hidden />
										<span>{ACROSS_THE_WORKSPACE}</span>
									</SidebarMenuSubButton>
								</SidebarMenuSubItem>
							</SidebarMenuSub>
						</CollapsibleContent>
					</Collapsible>
				)}
				{/* The icon-only sidebar hides the chevron and the sub list, so the view gets its own icon. */}
				{practicesEnabled && iconOnly && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip={ACROSS_THE_WORKSPACE}
							isActive={onAcrossTheWorkspace}
							render={acrossLink}
						>
							<ChartNoAxesGantt />
							<span>{ACROSS_THE_WORKSPACE}</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
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
