import { Link } from "@tanstack/react-router";
import { SquarePen } from "lucide-react";
import type { MouseEvent, ReactNode } from "react";

import type {
	ChatThreadSummary,
	IntegrationCatalogEntry,
	WorkspaceListItem,
	Workspace,
} from "@/api/types.gen";
import { NoWorkspace } from "@/components/common/NoWorkspace";
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
	/** Verified server metadata for instance administration, never an entry in the membership switcher. */
	/** The verified target identity remains even while its metadata is pending or refused. */
	administrationWorkspaceSlug?: string;
	administrationWorkspace?: Pick<Workspace, "displayName" | "providerType">;
	onWorkspaceChange?: (workspace: WorkspaceListItem) => void;
	onAddWorkspace?: () => void;
	workspacesLoading?: boolean;
	mentorThreads?: ChatThreadSummary[];
	mentorThreadsLoading?: boolean;
	mentorThreadsError?: string;
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
	administrationWorkspace,
	administrationWorkspaceSlug,
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
	let sidebarContent: ReactNode;
	if (administrationWorkspaceSlug !== undefined) {
		contextHeader = (
			<NavContextHeader
				title="Workspace administration"
				backLink={<Link to="/admin/workspaces" aria-label="Back to instance workspaces" />}
			/>
		);
	}

	if (context === "admin") {
		sidebarContent = <NavSuperAdmin />;
	} else if (administrationWorkspace && administrationWorkspaceSlug !== undefined) {
		contextHeader = (
			<NavContextHeader
				title={`${administrationWorkspace.displayName} administration`}
				backLink={<Link to="/admin/workspaces" aria-label="Back to instance workspaces" />}
			/>
		);
		sidebarContent = (
			<NavAdmin
				workspaceSlug={administrationWorkspaceSlug}
				integrationKinds={integrationKinds}
				awaitingApproval={awaitingApproval}
				scmProviderType={administrationWorkspace.providerType === "GITLAB" ? "GITLAB" : "GITHUB"}
			/>
		);
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
	} else if (administrationWorkspaceSlug !== undefined) {
		sidebarContent = (
			<SidebarGroup>
				<p className="text-sm text-muted-foreground">Workspace details are unavailable.</p>
			</SidebarGroup>
		);
	} else if (!activeWorkspace) {
		sidebarContent = (
			<div className="group-data-[collapsible=icon]:hidden">
				<NoWorkspace headingLevel={2} />
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
				{!readOnly && (
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
				)}
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
	}

	return (
		<Sidebar collapsible={context === "main" ? "icon" : "offcanvas"} {...props}>
			<SidebarHeader onClick={handleSectionClick}>
				{isMobile && <SidebarTrigger className="ml-auto" aria-label="Close navigation" />}
				{context !== "admin" && administrationWorkspaceSlug === undefined && (
					<WorkspaceSwitcher
						isLoading={workspacesLoading}
						workspaces={workspaces}
						activeWorkspace={activeWorkspace}
						onWorkspaceChange={onWorkspaceChange}
						onAddWorkspace={readOnly ? undefined : onAddWorkspace}
						isAppAdmin={isAppAdmin}
					/>
				)}
				{contextHeader}
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
