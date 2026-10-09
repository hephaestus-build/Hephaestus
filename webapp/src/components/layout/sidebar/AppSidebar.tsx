import type { MouseEvent, ReactNode } from "react";

import type { IntegrationCatalogEntry, WorkspaceListItem } from "@/api/types.gen";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import {
	Sidebar,
	SidebarContent,
	SidebarFooter,
	SidebarGroup,
	SidebarHeader,
	SidebarMenu,
	SidebarMenuItem,
	SidebarMenuSkeleton,
	SidebarRail,
	SidebarTrigger,
	useSidebar,
} from "@/components/ui/sidebar";

import { NavAdmin } from "./NavAdmin";
import { NavDashboards } from "./NavDashboards";
import { NavFooter } from "./NavFooter";
import { NavMentor } from "./NavMentor";
import { NavSuperAdmin } from "./NavSuperAdmin";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

export type SidebarContext = "main" | "admin";

export interface AppSidebarProps extends React.ComponentProps<typeof Sidebar> {
	isAdmin: boolean;
	isOwner?: boolean;
	isAppAdmin: boolean;
	/** Lists Heph in the main navigation of the active workspace; the container decides from membership and setup. */
	showMentor: boolean;
	readOnly?: boolean;
	integrationKinds: readonly IntegrationCatalogEntry["kind"][];
	/** Feedback awaiting approval in the active workspace, for an admin; undefined while unknown. */
	awaitingApproval?: number;
	context: SidebarContext;
	workspaces: WorkspaceListItem[];
	activeWorkspace?: WorkspaceListItem;
	onWorkspaceChange?: (workspace: WorkspaceListItem) => void;
	onAddWorkspace?: () => void;
	workspacesLoading?: boolean;
}

export function AppSidebar({
	isAdmin,
	isOwner = false,
	isAppAdmin,
	showMentor,
	readOnly = false,
	integrationKinds,
	awaitingApproval,
	context,
	workspaces,
	activeWorkspace,
	onWorkspaceChange,
	onAddWorkspace,
	workspacesLoading = false,
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
	let sidebarContent: ReactNode;

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
	} else if (activeWorkspace) {
		sidebarContent = (
			<>
				<NavDashboards
					workspaceSlug={activeWorkspace.workspaceSlug}
					practicesEnabled={activeWorkspace.practicesEnabled}
				/>
				{showMentor && <NavMentor workspaceSlug={activeWorkspace.workspaceSlug} />}
				{isAdmin && (
					<NavAdmin
						isOwner={isOwner}
						workspaceSlug={activeWorkspace.workspaceSlug}
						integrationKinds={integrationKinds}
						awaitingApproval={awaitingApproval}
						scmProviderType={activeWorkspace.providerType === "GITLAB" ? "GITLAB" : "GITHUB"}
					/>
				)}
			</>
		);
	} else {
		sidebarContent = (
			<div className="group-data-[collapsible=icon]:hidden">
				<NoWorkspace headingLevel={2} />
			</div>
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
						onAddWorkspace={readOnly ? undefined : onAddWorkspace}
						isAppAdmin={isAppAdmin}
					/>
				)}
			</SidebarHeader>
			<SidebarContent onClick={handleSectionClick}>{sidebarContent}</SidebarContent>
			<SidebarFooter onClick={handleSectionClick}>
				{!readOnly && (
					<NavFooter isAppAdmin={isAppAdmin} workspaceSlug={activeWorkspace?.workspaceSlug} />
				)}
			</SidebarFooter>
			<SidebarRail />
		</Sidebar>
	);
}
