import { ClockIcon, GitBranchIcon, GitCommitIcon } from "@primer/octicons-react";
import { Link } from "@tanstack/react-router";

import { cn } from "cn";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { optionalIntegrationsAvailable, requestConsentReopen } from "@/integrations/consent";
import { hasText } from "@/lib/text";

export interface FooterProps {
	className?: string;
	isProduction?: boolean;
	buildInfo?: {
		branch?: string;
		commit?: string;
		deployedAt?: string;
	};
}

export default function Footer({ className, isProduction, buildInfo }: FooterProps) {
	const showBuildInfo =
		!isProduction &&
		(hasText(buildInfo?.branch) || hasText(buildInfo?.commit) || hasText(buildInfo?.deployedAt));

	return (
		<footer className={cn("border-t border-sidebar-border bg-sidebar py-2", className)}>
			<div className="flex flex-col items-center justify-between gap-2 px-4 xl:flex-row">
				<p className="text-center text-sm text-muted-foreground xl:text-left">
					Built by{" "}
					<a
						href="https://github.com/ls1intum"
						target="_blank"
						rel="noopener noreferrer"
						className="font-medium underline underline-offset-4 hover:text-foreground"
					>
						AET Team
					</a>{" "}
					at{" "}
					<a
						href="https://www.tum.de/en/"
						target="_blank"
						rel="noopener noreferrer"
						className="font-medium underline underline-offset-4 hover:text-foreground"
					>
						TUM
					</a>
					. Source on{" "}
					<a
						href="https://github.com/hephaestus-build/Hephaestus"
						target="_blank"
						rel="noopener noreferrer"
						className="font-medium underline underline-offset-4 hover:text-foreground"
					>
						GitHub
					</a>
					.
				</p>

				<div className="flex min-w-0 flex-wrap items-center justify-center gap-4">
					<nav className="flex flex-wrap justify-center gap-4 sm:gap-6">
						<Link
							to="/about"
							className="text-sm text-muted-foreground hover:text-foreground hover:underline underline-offset-4"
						>
							About
						</Link>
						<a
							href="https://github.com/hephaestus-build/Hephaestus/releases"
							target="_blank"
							rel="noopener noreferrer"
							className="text-sm text-muted-foreground hover:text-foreground hover:underline underline-offset-4"
						>
							Releases
						</a>
						<Link
							to="/privacy"
							className="text-sm text-muted-foreground hover:text-foreground hover:underline underline-offset-4"
						>
							Privacy
						</Link>
						<Link
							to="/imprint"
							className="text-sm text-muted-foreground hover:text-foreground hover:underline underline-offset-4"
						>
							Imprint
						</Link>
						{optionalIntegrationsAvailable && (
							<button
								type="button"
								onClick={() => requestConsentReopen()}
								className="text-sm text-muted-foreground hover:text-foreground hover:underline underline-offset-4"
							>
								Cookie preferences
							</button>
						)}
					</nav>

					{showBuildInfo && (
						<div className="hidden items-center gap-2 border-l border-sidebar-border pl-4 font-mono text-xs text-muted-foreground xl:flex">
							{buildInfo.branch && (
								<Tooltip>
									<TooltipTrigger
										render={
											<a
												href={`https://github.com/hephaestus-build/Hephaestus/tree/${buildInfo.branch}`}
												target="_blank"
												rel="noopener noreferrer"
												aria-label={`View branch ${buildInfo.branch}`}
												className="flex items-center gap-1 hover:text-foreground transition-colors"
											/>
										}
									>
										<GitBranchIcon size={12} />
										<span className="max-w-20 truncate">{buildInfo.branch}</span>
									</TooltipTrigger>
									<TooltipContent>{buildInfo.branch}</TooltipContent>
								</Tooltip>
							)}

							{buildInfo.commit && (
								<Tooltip>
									<TooltipTrigger
										render={
											<a
												href={`https://github.com/hephaestus-build/Hephaestus/commit/${buildInfo.commit}`}
												target="_blank"
												rel="noopener noreferrer"
												aria-label={`View commit ${buildInfo.commit.substring(0, 7)}`}
												className="flex items-center gap-1 hover:text-foreground transition-colors"
											/>
										}
									>
										<GitCommitIcon size={12} />
										<span>{buildInfo.commit.substring(0, 7)}</span>
									</TooltipTrigger>
									<TooltipContent>{buildInfo.commit}</TooltipContent>
								</Tooltip>
							)}

							{buildInfo.deployedAt && (
								<span className="flex items-center gap-1">
									<ClockIcon size={12} aria-hidden />
									<RelativeTime value={buildInfo.deployedAt} />
								</span>
							)}
						</div>
					)}
				</div>
			</div>
		</footer>
	);
}
