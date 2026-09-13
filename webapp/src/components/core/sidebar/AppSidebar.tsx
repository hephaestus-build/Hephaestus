import { Link } from "@tanstack/react-router";
import { SquarePen } from "lucide-react";
import type { MouseEvent, ReactNode } from "react";

import type {
	ChatThreadSummary,
	IntegrationCatalogEntry,
	WorkspaceListItem,
} from "@/api/types.gen";
import {
	Sidebar,
	SidebarContent,
	SidebarFooter,
	SidebarGroup,
	SidebarHeader,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarMenuSkeleton,
	SidebarRail,
	SidebarTrigger,
	useSidebar,
} from "@/components/ui/sidebar";
import { NoWorkspace } from "@/components/workspace/NoWorkspace";

import { NavAdmin } from "./NavAdmin";
import { NavContextHeader } from "./NavContextHeader";
import { NavDashboards } from "./NavDashboards";
import { NavFooter } from "./NavFooter";
import { NavMentor } from "./NavMentor";
import { NavMentorThreads } from "./NavMentorThreads";
import { NavSuperAdmin } from "./NavSuperAdmin";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

export type SidebarContext = "main" | "mentor" | "admin";

export interface AppSidebarProps extends React.ComponentProps<typeof Sidebar> {
	username: string;
	isAdmin: boolean;
	isOwner?: boolean;
	isAppAdmin: boolean;
	hasMentorAccess: boolean;
	integrationKinds: ReadonlyArray<IntegrationCatalogEntry["kind"]>;
	context: SidebarContext;
	workspaces: WorkspaceListItem[];
	activeWorkspace?: WorkspaceListItem;
	onWorkspaceChange?: (workspace: WorkspaceListItem) => void;
	onAddWorkspace?: () => void;
	workspacesLoading?: boolean;
	mentorThreads?: ChatThreadSummary[];
	mentorThreadsLoading?: boolean;
	mentorThreadsError?: string;
}

export function AppSidebar({
	username,
	isAdmin,
	isOwner = false,
	isAppAdmin,
	hasMentorAccess,
	integrationKinds,
	context,
	workspaces,
	activeWorkspace,
	onWorkspaceChange,
	onAddWorkspace,
	workspacesLoading = false,
	mentorThreads,
	mentorThreadsLoading,
	mentorThreadsError,
	onClick,
	...props
}: AppSidebarProps) {
	const { isMobile, setOpenMobile } = useSidebar();
	const handleSectionClick = (event: MouseEvent<HTMLDivElement>) => {
		onClick?.(event);
		if (isMobile && event.target instanceof Element && event.target.closest("a")) {
			setOpenMobile(false);
		}
	};
	let contextHeader: ReactNode = null;
	let sidebarContent: ReactNode = null;

	if (context === "admin") {
		sidebarContent = <NavSuperAdmin />;
	} else if (workspacesLoading) {
		sidebarContent = (
			<SidebarGroup>
				<SidebarMenu>
					{Array.from({ length: 5 }).map((_, index) => (
						<SidebarMenuItem key={index}>
							<SidebarMenuSkeleton showIcon />
						</SidebarMenuItem>
					))}
				</SidebarMenu>
			</SidebarGroup>
		);
	} else if (!activeWorkspace) {
		sidebarContent = (
			<div className="group-data-[collapsible=icon]:hidden">
				<NoWorkspace />
			</div>
		);
	} else if (context === "mentor") {
		contextHeader = (
			<NavContextHeader
				title="Mentor"
				backLink={
					<Link to="/w/$workspaceSlug" params={{ workspaceSlug: activeWorkspace.workspaceSlug }} />
				}
			>
				<SidebarMenuButton
					render={
						<Link
							to="/w/$workspaceSlug/mentor"
							params={{ workspaceSlug: activeWorkspace.workspaceSlug }}
						/>
					}
				>
					<SquarePen />
					New chat
				</SidebarMenuButton>
			</NavContextHeader>
		);
		sidebarContent = (
			<NavMentorThreads
				workspaceSlug={activeWorkspace.workspaceSlug}
				threads={mentorThreads ?? []}
				isLoading={mentorThreadsLoading}
				error={mentorThreadsError}
			/>
		);
	} else {
		sidebarContent = (
			<>
				<NavDashboards
					username={username}
					workspaceSlug={activeWorkspace.workspaceSlug}
					leaderboardEnabled={activeWorkspace.leaderboardEnabled}
					practicesEnabled={activeWorkspace.practicesEnabled}
				/>
				{hasMentorAccess && activeWorkspace.mentorEnabled && (
					<NavMentor workspaceSlug={activeWorkspace.workspaceSlug} />
				)}
				{isAdmin && (
					<NavAdmin
						isOwner={isOwner}
						workspaceSlug={activeWorkspace.workspaceSlug}
						integrationKinds={integrationKinds}
						scmProviderType={activeWorkspace.providerType === "GITLAB" ? "GITLAB" : "GITHUB"}
					/>
				)}
			</>
		);
	}

	return (
		<Sidebar collapsible={context === "main" ? "icon" : "offcanvas"} {...props}>
			<SidebarHeader onClick={handleSectionClick}>
				{isMobile && <SidebarTrigger className="ml-auto" aria-label="Close navigation" />}
				{context !== "admin" && (
					<WorkspaceSwitcher
						isLoading={workspacesLoading}
						workspaces={workspaces}
						activeWorkspace={activeWorkspace}
						onWorkspaceChange={onWorkspaceChange}
						onAddWorkspace={onAddWorkspace}
						isAppAdmin={isAppAdmin}
					/>
				)}
				{contextHeader}
			</SidebarHeader>
			<SidebarContent onClick={handleSectionClick}>{sidebarContent}</SidebarContent>
			<SidebarFooter onClick={handleSectionClick}>
				<NavFooter isAppAdmin={isAppAdmin} workspaceSlug={activeWorkspace?.workspaceSlug} />
			</SidebarFooter>
			<SidebarRail />
		</Sidebar>
	);
}
