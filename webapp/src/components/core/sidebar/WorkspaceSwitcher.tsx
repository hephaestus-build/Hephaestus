import { ChevronsUpDown, Plus } from "lucide-react";
import { useEffect, useState } from "react";

import type { WorkspaceListItem } from "@/api/types.gen";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuLabel,
	DropdownMenuSeparator,
	DropdownMenuShortcut,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	useSidebar,
} from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { getWorkspaceAvatarUrl } from "@/lib/provider";
import { firstNonBlank } from "@/lib/text";

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

	useEffect(() => {
		const handleKeyDown = (event: KeyboardEvent) => {
			if (event.metaKey || event.ctrlKey) {
				const keyNum = Number.parseInt(event.key, 10);
				if (!Number.isNaN(keyNum) && keyNum >= 1 && keyNum <= 9) {
					const selectedWorkspace = workspaces[keyNum - 1];
					if (selectedWorkspace) {
						event.preventDefault();
						onWorkspaceChange?.(selectedWorkspace);
					}
				}
			}
		};

		document.addEventListener("keydown", handleKeyDown);
		return () => {
			document.removeEventListener("keydown", handleKeyDown);
		};
	}, [workspaces, onWorkspaceChange]);

	if (isLoading) {
		return (
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton size="lg" disabled aria-label="Loading workspaces">
						<Skeleton className="aspect-square size-8 rounded-lg" />
						<div className="grid flex-1 text-left text-sm leading-tight gap-1 group-data-[collapsible=icon]:hidden">
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
							<div className="grid flex-1 text-left text-sm leading-tight group-data-[collapsible=icon]:hidden">
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
						<div className="grid flex-1 text-left text-sm leading-tight group-data-[collapsible=icon]:hidden">
							<span className="truncate font-semibold">No workspace</span>
						</div>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		);
	}

	return (
		<SidebarMenu>
			<SidebarMenuItem>
				<DropdownMenu open={isDropdownOpen} onOpenChange={setIsDropdownOpen}>
					<DropdownMenuTrigger render={<SidebarMenuButton size="lg" className="" />}>
						<div className="flex aspect-square size-8 items-center justify-center rounded-lg text-sidebar-primary-foreground">
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
									alt={activeWorkspace?.displayName}
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
						<div className="grid flex-1 text-left text-sm leading-tight group-data-[collapsible=icon]:hidden">
							<span className="truncate font-semibold">
								{activeWorkspace?.displayName ?? "No workspace"}
							</span>
							<span className="truncate text-xs text-muted-foreground">
								{activeWorkspace?.accountLogin ?? "Select a workspace"}
							</span>
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
						</DropdownMenuGroup>
						{workspaces.map((workspace, index) => (
							<DropdownMenuItem
								key={workspace.workspaceSlug}
								onClick={() => onWorkspaceChange?.(workspace)}
								className="gap-2 p-2"
							>
								<div className="flex size-6 items-center justify-center rounded-sm overflow-clip">
									<Avatar className="size-6 rounded-sm">
										<AvatarImage
											src={
												getWorkspaceAvatarUrl(
													workspace.providerType ?? "GITHUB",
													workspace.accountLogin,
												) ?? undefined
											}
											alt={workspace.displayName}
										/>
										<AvatarFallback className="rounded-sm text-xs bg-sidebar-primary text-sidebar-primary-foreground">
											{(workspace.displayName || workspace.workspaceSlug).slice(0, 2).toUpperCase()}
										</AvatarFallback>
									</Avatar>
								</div>
								<div className="flex flex-col leading-tight">
									<span className="font-medium">{workspace.displayName}</span>
									<span className="text-2xs text-muted-foreground">{workspace.accountLogin}</span>
								</div>
								<DropdownMenuShortcut>
									{navigator.platform.toLowerCase().includes("mac") ? "⌘" : "Ctrl"}
									{index + 1}
								</DropdownMenuShortcut>
							</DropdownMenuItem>
						))}
						<DropdownMenuSeparator />
						<DropdownMenuItem className="gap-2 p-2" onClick={() => onAddWorkspace?.()}>
							<div className="flex size-6 items-center justify-center rounded-md border bg-background">
								<Plus className="size-4" />
							</div>
							<div className="font-medium text-muted-foreground">Add workspace</div>
						</DropdownMenuItem>
					</DropdownMenuContent>
				</DropdownMenu>
			</SidebarMenuItem>
		</SidebarMenu>
	);
}
