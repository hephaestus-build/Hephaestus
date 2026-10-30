import { TagIcon } from "@primer/octicons-react";
import { Link } from "@tanstack/react-router";
import { Activity, LogOut, Settings } from "lucide-react";

import { cn } from "cn";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { ModeToggle } from "@/components/layout/ModeToggle";
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
import { firstNonBlank, hasText } from "@/lib/text";
import type { Session } from "@/runtime/auth/AuthContext";
import { type EnvironmentTone, resolveHeaderBadge } from "./version";

const ENV_DOT: Record<EnvironmentTone, string> = {
	staging: "bg-warning",
	preview: "bg-highlight",
	local: "bg-muted-foreground/50",
};

export interface HeaderProps {
	sidebarTrigger?: React.ReactNode;
	version: string;
	environmentName: string;
	isProduction: boolean;
	/** The pull request a preview deployment is of; absent on every other environment. */
	pullRequest?: number;
	/** Unavailable means that the server cannot say who is signed in, so the header offers no sign-in. */
	session: Session;
	name?: string;
	username?: string;
	avatarUrl?: string;
	workspaceSlug?: string;
	readOnly?: boolean;
	feedbackDialog?: React.ReactNode;
	onLogin: () => void;
	onLogout: () => void;
	/** Reads the session again when the server could not answer. */
	onRetrySession: () => void;
}

export default function Header({
	sidebarTrigger,
	version,
	environmentName,
	isProduction,
	pullRequest,
	session,
	name,
	username,
	workspaceSlug,
	readOnly = false,
	feedbackDialog,
	avatarUrl,
	onLogin,
	onLogout,
	onRetrySession,
}: HeaderProps) {
	const hasWorkspace = Boolean(workspaceSlug);
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

	const isAuthenticated = session === "signed-in";

	return (
		<header className="flex h-16 shrink-0 items-center justify-between gap-2 transition-[width,height] ease-linear group-has-data-[collapsible=icon]/sidebar-wrapper:h-12">
			<div className="flex items-center gap-1 px-2 sm:gap-2 sm:px-4">
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
										className="mt-1 hidden items-center gap-1 font-mono text-xs text-muted-foreground transition-colors hover:text-foreground sm:flex"
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
										variant="muted"
										className="hidden sm:inline-flex"
										render={
											hasText(badge.href) ? (
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
			<div className="flex gap-1 px-2 sm:gap-2 sm:px-4">
				{isAuthenticated ? feedbackDialog : null}
				<ModeToggle />
				<div className="flex items-center gap-1 sm:gap-2">
					{isAuthenticated ? (
						<div className="flex items-center gap-1 sm:gap-2">
							<DropdownMenu>
								<DropdownMenuTrigger
									aria-label="Account"
									render={<Button variant="ghost" size="icon" shape="pill" />}
								>
									<Avatar className="hover:brightness-90">
										<AvatarImage
											src={firstNonBlank(avatarUrl)}
											alt={hasText(username) ? `${username}’s avatar` : ""}
										/>
										<AvatarFallback>{getInitials(name, username)}</AvatarFallback>
									</Avatar>
								</DropdownMenuTrigger>
								<DropdownMenuContent className="w-56" align="end">
									<DropdownMenuGroup>
										<DropdownMenuLabel className="font-normal">
											<div className="flex flex-col space-y-1">
												<p className="text-sm leading-none font-medium">{name}</p>
											</div>
										</DropdownMenuLabel>
									</DropdownMenuGroup>
									<DropdownMenuSeparator />
									{!readOnly && (
										<>
											<DropdownMenuGroup>
												{hasWorkspace ? (
													<DropdownMenuItem
														render={
															<Link
																to="/w/$workspaceSlug/activity"
																params={{ workspaceSlug: workspaceSlug ?? "" }}
																className="[&]:no-underline"
															/>
														}
													>
														<Activity />
														<span>Activity</span>
													</DropdownMenuItem>
												) : (
													<DropdownMenuItem disabled title="Join a workspace to see your activity">
														<Activity />
														<span>Activity</span>
													</DropdownMenuItem>
												)}
												<DropdownMenuItem
													render={
														<Link
															to="/settings"
															search={{ chromeWorkspace: workspaceSlug }}
															className="[&]:no-underline"
														/>
													}
												>
													<Settings />
													<span>Settings</span>
												</DropdownMenuItem>
											</DropdownMenuGroup>
											<DropdownMenuSeparator />
										</>
									)}
									<DropdownMenuItem onClick={onLogout}>
										<LogOut />
										<span>Sign out</span>
									</DropdownMenuItem>
								</DropdownMenuContent>
							</DropdownMenu>
						</div>
					) : (
						session !== "unavailable" && (
							<Button variant="outline" onClick={onLogin} disabled={session === "loading"}>
								Sign in
							</Button>
						)
					)}
					{/* Mounted before its text, so that screen readers announce the change. */}
					<p
						aria-live="polite"
						className="text-xs leading-tight text-muted-foreground empty:sr-only max-sm:max-w-16 sm:text-sm"
					>
						{session === "unavailable" ? (
							<>
								{/* A phone has room for the short forms; only the shown one is read out. */}
								<span className="sm:hidden">Not reachable</span>
								<span className="max-sm:hidden">Hephaestus is not reachable right now</span>
							</>
						) : null}
					</p>
					{session === "unavailable" && (
						<Button variant="outline" size="sm" onClick={onRetrySession}>
							<span className="sm:hidden">Retry</span>
							<span className="max-sm:hidden">Try again</span>
						</Button>
					)}
				</div>
			</div>
		</header>
	);
}
