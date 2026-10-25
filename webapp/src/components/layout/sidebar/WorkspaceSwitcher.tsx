import { ChevronsUpDown, Plus } from "lucide-react";
import { useState } from "react";

import type { WorkspaceListItem } from "@/api/types.gen";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuLabel,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	useSidebar,
} from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { getWorkspaceAvatarUrl } from "@/lib/provider/avatar";
import { firstNonBlank } from "@/lib/text";

/**
 * The account login shown under a workspace's name, unless it only repeats the name. The comparison is
 * exact, so a login that differs in case still tells two workspaces apart.
 */
function distinctLogin(workspace: Pick<WorkspaceListItem, "displayName" | "accountLogin">) {
	return workspace.accountLogin.trim() === workspace.displayName.trim()
		? undefined
		: workspace.accountLogin;
}

export function WorkspaceSwitcher({
	workspaces,
	activeWorkspace,
	onWorkspaceChange,
	onAddWorkspace,
	isLoading = false,
	isAppAdmin = false,
}: {
	workspaces: WorkspaceListItem[];
	activeWorkspace?: WorkspaceListItem;
	onWorkspaceChange?: (workspace: WorkspaceListItem) => void;
	onAddWorkspace?: () => void;
	isLoading?: boolean;
	/** Only gates the zero-workspace state, where no workspace exists to carry a membership role. */
	isAppAdmin?: boolean;
}) {
	const { isMobile } = useSidebar();
	const [isDropdownOpen, setIsDropdownOpen] = useState(false);

	if (isLoading) {
		return (
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton size="lg" disabled aria-label="Loading workspaces">
						<Skeleton className="aspect-square size-8 rounded-lg" />
						<div className="grid flex-1 gap-1 text-left text-sm leading-tight group-data-[collapsible=icon]:hidden">
							<Skeleton className="h-3 w-20" />
							<Skeleton className="h-2.5 w-12" />
						</div>
						<ChevronsUpDown className="ml-auto size-4 opacity-50 group-data-[collapsible=icon]:hidden" />
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		);
	}

	if (workspaces.length === 0) {
		if (isAppAdmin) {
			return (
				<SidebarMenu>
					<SidebarMenuItem>
						<SidebarMenuButton onClick={onAddWorkspace} size="lg">
							<div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground">
								<Plus className="size-4" />
							</div>
							<div className="grid min-w-0 flex-1 text-left text-sm leading-tight group-data-[collapsible=icon]:sr-only">
								<span className="truncate font-semibold">Create workspace</span>
							</div>
						</SidebarMenuButton>
					</SidebarMenuItem>
				</SidebarMenu>
			);
		}

		return (
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton disabled size="lg">
						<div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-muted text-muted-foreground">
							<ChevronsUpDown className="size-4" />
						</div>
						<div className="grid flex-1 text-left text-sm leading-tight group-data-[collapsible=icon]:sr-only">
							<span className="truncate font-semibold">No workspace</span>
						</div>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		);
	}

	const activeLogin = activeWorkspace ? distinctLogin(activeWorkspace) : "Select a workspace";

	return (
		<SidebarMenu>
			<SidebarMenuItem>
				<DropdownMenu open={isDropdownOpen} onOpenChange={setIsDropdownOpen}>
					<DropdownMenuTrigger render={<SidebarMenuButton size="lg" className="h-auto min-h-12" />}>
						{/* The avatar repeats the name beside it, so it is not part of the button's name. */}
						<div
							aria-hidden="true"
							className="flex aspect-square size-8 items-center justify-center rounded-lg text-sidebar-primary-foreground"
						>
							<Avatar className="size-8 rounded-lg">
								<AvatarImage
									src={
										activeWorkspace
											? (getWorkspaceAvatarUrl(
													activeWorkspace.providerType ?? "GITHUB",
													activeWorkspace.accountLogin,
												) ?? undefined)
											: undefined
									}
									alt=""
								/>
								<AvatarFallback className="rounded-lg bg-sidebar-primary text-sidebar-primary-foreground">
									{(
										firstNonBlank(activeWorkspace?.displayName, activeWorkspace?.workspaceSlug) ??
										"WS"
									)
										.slice(0, 2)
										.toUpperCase()}
								</AvatarFallback>
							</Avatar>
						</div>
						{/* Collapsed to an icon, the words leave the screen but stay the button's name. */}
						<div className="grid flex-1 text-left text-sm leading-tight group-data-[collapsible=icon]:sr-only">
							<span className="font-semibold wrap-anywhere">
								{activeWorkspace?.displayName ?? "No workspace"}
							</span>
							{activeLogin !== undefined && (
								<span className="text-xs wrap-anywhere text-muted-foreground">{activeLogin}</span>
							)}
						</div>
						<ChevronsUpDown className="ml-auto group-data-[collapsible=icon]:hidden" />
					</DropdownMenuTrigger>
					<DropdownMenuContent
						className="w-(--anchor-width) min-w-56"
						align="start"
						side={isMobile ? "bottom" : "right"}
						sideOffset={4}
					>
						<DropdownMenuGroup>
							<DropdownMenuLabel className="text-xs text-muted-foreground">
								Workspaces
							</DropdownMenuLabel>
							{workspaces.map((workspace) => {
								const login = distinctLogin(workspace);
								return (
									<DropdownMenuItem
										key={workspace.workspaceSlug}
										onClick={() => onWorkspaceChange?.(workspace)}
										className="gap-2 p-2"
									>
										<div
											aria-hidden="true"
											className="flex size-6 items-center justify-center overflow-clip rounded-sm"
										>
											<Avatar className="size-6 rounded-sm">
												<AvatarImage
													src={
														getWorkspaceAvatarUrl(
															workspace.providerType ?? "GITHUB",
															workspace.accountLogin,
														) ?? undefined
													}
													alt=""
												/>
												<AvatarFallback className="rounded-sm bg-sidebar-primary text-xs text-sidebar-primary-foreground">
													{(workspace.displayName || workspace.workspaceSlug)
														.slice(0, 2)
														.toUpperCase()}
												</AvatarFallback>
											</Avatar>
										</div>
										<div className="flex min-w-0 flex-col leading-tight">
											<span className="font-medium wrap-anywhere">{workspace.displayName}</span>
											{login !== undefined && (
												<span className="text-2xs wrap-anywhere text-muted-foreground">
													{login}
												</span>
											)}
										</div>
									</DropdownMenuItem>
								);
							})}
						</DropdownMenuGroup>
						{onAddWorkspace && (
							<>
								<DropdownMenuSeparator />
								<DropdownMenuGroup>
									<DropdownMenuItem className="gap-2 p-2" onClick={onAddWorkspace}>
										<div className="flex size-6 items-center justify-center rounded-md border bg-background">
											<Plus className="size-4" />
										</div>
										<div className="font-medium text-muted-foreground">Create workspace</div>
									</DropdownMenuItem>
								</DropdownMenuGroup>
							</>
						)}
					</DropdownMenuContent>
				</DropdownMenu>
			</SidebarMenuItem>
		</SidebarMenu>
	);
}
