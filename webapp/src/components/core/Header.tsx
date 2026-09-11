import { TagIcon } from "@primer/octicons-react";
import { Link } from "@tanstack/react-router";
import { LogOut, Settings, User } from "lucide-react";

import { cn } from "cn";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { ModeToggle } from "@/components/core/ModeToggle";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuLabel,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { getInitials } from "@/lib/avatar";
import { firstNonBlank } from "@/lib/text";
import { type EnvironmentTone, resolveHeaderBadge } from "@/lib/version";

const ENV_DOT: Record<EnvironmentTone, string> = {
	staging: "bg-amber-500 dark:bg-amber-400",
	preview: "bg-violet-500 dark:bg-violet-400",
	local: "bg-muted-foreground/50",
};

export interface HeaderProps {
	sidebarTrigger?: React.ReactNode;
	version: string;
	environmentName: string;
	isProduction: boolean;
	/** The pull request a preview deployment is of; absent on every other environment. */
	pullRequest?: number;
	isAuthenticated: boolean;
	isLoading: boolean;
	name?: string;
	username?: string;
	avatarUrl?: string;
	workspaceSlug?: string;
	feedbackDialog?: React.ReactNode;
	onLogin: () => void;
	onLogout: () => void;
}

export default function Header({
	sidebarTrigger,
	version,
	environmentName,
	isProduction,
	pullRequest,
	isAuthenticated,
	isLoading,
	name,
	username,
	workspaceSlug,
	feedbackDialog,
	avatarUrl,
	onLogin,
	onLogout,
}: HeaderProps) {
	const hasWorkspace = Boolean(workspaceSlug);
	const hasUsername = Boolean(username);
	const badge = resolveHeaderBadge(version, environmentName, isProduction, pullRequest);
	const logo = (
		<span className="inline-flex">
			<HephaestusLogo
				className="gap-1 sm:gap-2"
				markClassName="size-8"
				wordmarkClassName="text-base sm:text-xl"
			/>
		</span>
	);

	return (
		<header className="flex h-16 shrink-0 items-center gap-2 transition-[width,height] ease-linear group-has-data-[collapsible=icon]/sidebar-wrapper:h-12 justify-between">
			<div className="flex items-center gap-2 px-2 sm:px-4">
				{sidebarTrigger}
				<div className="flex items-center gap-2">
					{hasWorkspace ? (
						<Link
							to="/w/$workspaceSlug"
							params={{ workspaceSlug: workspaceSlug ?? "" }}
							aria-label="Hephaestus home"
							className="group/logo flex items-center gap-2 rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
						>
							{logo}
						</Link>
					) : (
						<Link
							to="/"
							aria-label="Hephaestus home"
							className="group/logo flex items-center gap-2 rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
						>
							{logo}
						</Link>
					)}
					{badge.kind === "release" ? (
						<Tooltip>
							<TooltipTrigger
								render={
									<a
										href={badge.href}
										target="_blank"
										rel="noopener noreferrer"
										aria-label={badge.ariaLabel}
										className="mt-1 hidden items-center gap-1 font-mono text-muted-foreground text-xs transition-colors hover:text-foreground sm:flex"
									/>
								}
							>
								<TagIcon size={12} />
								<span>{badge.label}</span>
							</TooltipTrigger>
							<TooltipContent>{badge.tooltip}</TooltipContent>
						</Tooltip>
					) : (
						<Tooltip>
							<TooltipTrigger
								render={
									<Badge
										variant="outline"
										className="hidden gap-1.5 font-normal text-muted-foreground sm:inline-flex"
										render={
											badge.href ? (
												<a href={badge.href} target="_blank" rel="noopener noreferrer" />
											) : undefined
										}
									/>
								}
							>
								<span className={cn("size-1.5 rounded-full", ENV_DOT[badge.tone])} />
								{badge.label}
							</TooltipTrigger>
							<TooltipContent>{badge.tooltip}</TooltipContent>
						</Tooltip>
					)}
				</div>
			</div>
			<div className="flex gap-2 px-2 sm:px-4">
				{isAuthenticated ? feedbackDialog : null}
				<ModeToggle />
				<div className="flex items-center gap-2">
					{!isAuthenticated ? (
						<Button variant="outline" onClick={onLogin} disabled={isLoading}>
							Sign in
						</Button>
					) : (
						<div className="flex items-center gap-2">
							<DropdownMenu>
								<DropdownMenuTrigger
									aria-label="Account"
									render={<Button variant="ghost" size="icon" className="rounded-full" />}
								>
									<Avatar className="hover:brightness-90">
										<AvatarImage src={firstNonBlank(avatarUrl)} alt={`${username}'s avatar`} />
										<AvatarFallback>{getInitials(name, username)}</AvatarFallback>
									</Avatar>
								</DropdownMenuTrigger>
								<DropdownMenuContent className="w-56" align="end">
									<DropdownMenuGroup>
										<DropdownMenuLabel className="font-normal">
											<div className="flex flex-col space-y-1">
												<p className="text-sm font-medium leading-none">{name}</p>
											</div>
										</DropdownMenuLabel>
									</DropdownMenuGroup>
									<DropdownMenuSeparator />
									<DropdownMenuGroup>
										{hasWorkspace && hasUsername ? (
											<Link
												to="/w/$workspaceSlug/user/$username"
												params={{
													workspaceSlug: workspaceSlug ?? "",
													username: username ?? "",
												}}
												className="[&]:no-underline"
											>
												<DropdownMenuItem>
													<User />
													<span>My Profile</span>
												</DropdownMenuItem>
											</Link>
										) : (
											<DropdownMenuItem disabled title="Join a workspace to view your profile">
												<User />
												<span>My Profile</span>
											</DropdownMenuItem>
										)}
										<Link to="/settings" className="[&]:no-underline">
											<DropdownMenuItem>
												<Settings />
												<span>Settings</span>
											</DropdownMenuItem>
										</Link>
									</DropdownMenuGroup>
									<DropdownMenuSeparator />
									<DropdownMenuItem onClick={onLogout}>
										<LogOut />
										<span>Sign Out</span>
									</DropdownMenuItem>
								</DropdownMenuContent>
							</DropdownMenu>
						</div>
					)}
				</div>
			</div>
		</header>
	);
}
