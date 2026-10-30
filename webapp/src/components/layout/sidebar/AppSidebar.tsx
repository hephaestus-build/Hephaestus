import { Link } from "@tanstack/react-router";
import type { MouseEvent, ReactNode } from "react";

import type { IntegrationCatalogEntry, WorkspaceListItem, Workspace } from "@/api/types.gen";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { Button } from "@/components/ui/button";
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
	SidebarSeparator,
	SidebarTrigger,
	useSidebar,
} from "@/components/ui/sidebar";
import {
	PROBLEM_COPY,
	type ProblemCopy,
	problemIsRetryable,
	problemKindOf,
	problemStatusOf,
} from "@/lib/problem-detail";

import { NavAdmin } from "./NavAdmin";
import { NavContextHeader } from "./NavContextHeader";
import { NavFooter } from "./NavFooter";
import { NavSuperAdmin } from "./NavSuperAdmin";
import { NavWorkspace } from "./NavWorkspace";
import { NavYou } from "./NavYou";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

export type SidebarContext = "main" | "admin";

export interface AppSidebarProps extends React.ComponentProps<typeof Sidebar> {
	isAdmin: boolean;
	isOwner?: boolean;
	isAppAdmin: boolean;
	/** Lists Ask Hephaestus in the main navigation of the active workspace; the container decides from membership and setup. */
	showConversation: boolean;
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
	/** Why the administration workspace's details did not load, and how to read them again. Read only without those details. */
	administrationWorkspaceError?: { error: unknown; onRetry: () => void };
	onWorkspaceChange?: (workspace: WorkspaceListItem) => void;
	onAddWorkspace?: () => void;
	workspacesLoading?: boolean;
}

export function AppSidebar({
	isAdmin,
	isOwner = false,
	isAppAdmin,
	showConversation,
	readOnly = false,
	integrationKinds,
	awaitingApproval,
	context,
	workspaces,
	activeWorkspace,
	administrationWorkspace,
	administrationWorkspaceSlug,
	administrationWorkspaceError,
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
		sidebarContent = <NavSuperAdmin workspaceSlug={activeWorkspace?.workspaceSlug} />;
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
	} else if (!workspacesLoading && administrationWorkspaceError !== undefined) {
		// The page reads the same workspace and announces the failure as an alert. The sidebar only
		// says why its links are missing, so the reader does not hear the failure twice.
		const { error, onRetry } = administrationWorkspaceError;
		const copy: ProblemCopy = PROBLEM_COPY[problemKindOf(problemStatusOf(error))];
		sidebarContent = (
			<SidebarGroup className="gap-2 px-4 group-data-[collapsible=icon]:hidden">
				<p className="text-sm text-muted-foreground">
					{copy.retitle?.("the workspace details") ?? "We could not load the workspace details"}
				</p>
				{problemIsRetryable(error) && (
					<div>
						<Button variant="outline" size="xs" onClick={onRetry}>
							Retry
						</Button>
					</div>
				)}
			</SidebarGroup>
		);
	} else if (workspacesLoading || administrationWorkspaceSlug !== undefined) {
		// Also an administration target whose details have neither arrived nor failed: still loading.
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
				<NavYou
					workspaceSlug={activeWorkspace.workspaceSlug}
					practicesEnabled={activeWorkspace.practicesEnabled}
					showConversation={showConversation}
					readOnly={readOnly}
				/>
				<SidebarSeparator className="hidden group-data-[collapsible=icon]:block" />
				<NavWorkspace
					workspaceSlug={activeWorkspace.workspaceSlug}
					practicesEnabled={activeWorkspace.practicesEnabled}
				/>
				{isAdmin && (
					<>
						<SidebarSeparator className="hidden group-data-[collapsible=icon]:block" />
						<NavAdmin
							isOwner={isOwner}
							workspaceSlug={activeWorkspace.workspaceSlug}
							integrationKinds={integrationKinds}
							awaitingApproval={awaitingApproval}
							scmProviderType={activeWorkspace.providerType === "GITLAB" ? "GITLAB" : "GITHUB"}
						/>
					</>
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
