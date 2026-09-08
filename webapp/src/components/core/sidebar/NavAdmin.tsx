import { Link, useMatchRoute } from "@tanstack/react-router";
import {
	BookUser,
	BrainCircuit,
	ChevronRight,
	CircleDollarSign,
	ClipboardCheck,
	LayoutGridIcon,
	KeyRound,
	ListChecks,
	Network,
	PlugZapIcon,
	ScanEye,
	ScrollText,
	Settings2,
	Users,
	Workflow,
} from "lucide-react";
import { type ReactElement, type ReactNode, useState } from "react";

import type { IntegrationCatalogEntry } from "@/api/types.gen";
import { ADMIN_NAV_LABELS } from "@/components/core/sidebar/admin-nav-labels";
import { GithubIcon, GitlabIcon, OutlineIcon, SlackIcon } from "@/components/icons/brand";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarMenuSub,
	SidebarMenuSubButton,
	SidebarMenuSubItem,
	useSidebar,
} from "@/components/ui/sidebar";

export interface NavAdminProps {
	workspaceSlug: string;
	integrationKinds: ReadonlyArray<IntegrationCatalogEntry["kind"]>;
	scmProviderType?: "GITHUB" | "GITLAB";
}

/**
 * Open state for a nav section, forced open when the user navigates into it and freely collapsible
 * the rest of the time. Adjusted during render, not in an effect, so arriving at an admin page
 * never paints the section collapsed first.
 */
function useSectionOpen(onSection: boolean) {
	const [open, setOpen] = useState(onSection);
	const [wasOnSection, setWasOnSection] = useState(onSection);

	if (onSection !== wasOnSection) {
		setWasOnSection(onSection);
		if (onSection) setOpen(true);
	}

	return [open, setOpen] as const;
}

export function NavAdmin({
	workspaceSlug,
	integrationKinds,
	scmProviderType = "GITHUB",
}: NavAdminProps) {
	const matchRoute = useMatchRoute();
	const { isMobile, state: sidebarState } = useSidebar();

	const onWorkspaceSettings = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/admin/settings", fuzzy: false }),
	);
	const onAccess = Boolean(matchRoute({ to: "/w/$workspaceSlug/admin/access", fuzzy: true }));
	const onGithubAccess = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/admin/github-access", fuzzy: true }),
	);
	const onDirectory = Boolean(matchRoute({ to: "/w/$workspaceSlug/admin/directory", fuzzy: true }));
	const onMembers = Boolean(matchRoute({ to: "/w/$workspaceSlug/admin/members", fuzzy: true }));
	const onTeams = Boolean(matchRoute({ to: "/w/$workspaceSlug/admin/teams", fuzzy: true }));
	const onReview = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/admin/practices/review", fuzzy: true }),
	);
	const onReviews = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/admin/practices/reviews", fuzzy: true }),
	);
	const onSection = Boolean(matchRoute({ to: "/w/$workspaceSlug/admin/practices", fuzzy: true }));
	// The catalogue is what is left when neither of the other two claimed the route, so it needs no
	// match of its own. `review` and `reviews` do not collide — the router matches whole segments —
	// but the section landing link is a prefix of all three, so it cannot be the test for any of them.
	const onCatalog = onSection && !onReview && !onReviews;

	const onIntegrationsScm = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/admin/integrations/scm", fuzzy: true }),
	);
	const onIntegrationsSlack = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/admin/integrations/slack", fuzzy: true }),
	);
	const onIntegrationsOutline = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/admin/integrations/outline", fuzzy: true }),
	);
	const onIntegrationsSection = Boolean(
		matchRoute({ to: "/w/$workspaceSlug/admin/integrations", fuzzy: true }),
	);
	const onIntegrationsOverview =
		onIntegrationsSection && !onIntegrationsScm && !onIntegrationsSlack && !onIntegrationsOutline;
	const onModels = Boolean(matchRoute({ to: "/w/$workspaceSlug/admin/models", fuzzy: true }));
	const onUsage = Boolean(matchRoute({ to: "/w/$workspaceSlug/admin/usage", fuzzy: true }));
	const onAudit = Boolean(matchRoute({ to: "/w/$workspaceSlug/admin/audit", fuzzy: true }));
	const [practicesOpen, setPracticesOpen] = useSectionOpen(onSection);
	const [integrationsOpen, setIntegrationsOpen] = useSectionOpen(onIntegrationsSection);
	const ScmIcon = scmProviderType === "GITLAB" ? GitlabIcon : GithubIcon;
	const scmLabel = scmProviderType === "GITLAB" ? "GitLab" : "GitHub";
	const scmKind = scmProviderType === "GITLAB" ? "GITLAB" : "GITHUB";

	return (
		<SidebarGroup>
			<SidebarGroupLabel>Administration</SidebarGroupLabel>
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Workspace settings"
						isActive={onWorkspaceSettings}
						render={<Link to="/w/$workspaceSlug/admin/settings" params={{ workspaceSlug }} />}
					>
						<Settings2 />
						<span>Settings</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Access"
						isActive={onAccess}
						render={<Link to="/w/$workspaceSlug/admin/access" params={{ workspaceSlug }} />}
					>
						<KeyRound />
						<span>Access</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Members"
						isActive={onMembers}
						render={<Link to="/w/$workspaceSlug/admin/members" params={{ workspaceSlug }} />}
					>
						<BookUser />
						<span>Members</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Directory access"
						isActive={onDirectory}
						render={<Link to="/w/$workspaceSlug/admin/directory" params={{ workspaceSlug }} />}
					>
						<Network />
						<span>Directory access</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Teams"
						isActive={onTeams}
						render={<Link to="/w/$workspaceSlug/admin/teams" params={{ workspaceSlug }} />}
					>
						<Users />
						<span>Teams</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="GitHub access"
						isActive={onGithubAccess}
						render={<Link to="/w/$workspaceSlug/admin/github-access" params={{ workspaceSlug }} />}
					>
						<GithubIcon />
						<span>GitHub access</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<AdminNavSection
					label="Practices"
					icon={<ClipboardCheck />}
					active={onSection}
					open={practicesOpen}
					onOpenChange={setPracticesOpen}
					collapsed={!isMobile && sidebarState === "collapsed"}
					landingLink={
						<Link
							to="/w/$workspaceSlug/admin/practices"
							params={{ workspaceSlug }}
							activeOptions={{ exact: true }}
							aria-current={onCatalog ? "page" : undefined}
						/>
					}
				>
					<SidebarMenuSubItem>
						<SidebarMenuSubButton
							isActive={onCatalog}
							render={
								<Link
									to="/w/$workspaceSlug/admin/practices"
									params={{ workspaceSlug }}
									activeOptions={{ exact: true }}
									aria-current={onCatalog ? "page" : undefined}
								/>
							}
						>
							<ListChecks />
							<span>Practice setup</span>
						</SidebarMenuSubButton>
					</SidebarMenuSubItem>
					<SidebarMenuSubItem>
						<SidebarMenuSubButton
							isActive={onReview}
							render={
								<Link
									to="/w/$workspaceSlug/admin/practices/review"
									params={{ workspaceSlug }}
									search={{}}
								/>
							}
						>
							<ScanEye />
							<span>Review</span>
						</SidebarMenuSubButton>
					</SidebarMenuSubItem>
					<SidebarMenuSubItem>
						<SidebarMenuSubButton
							isActive={onReviews}
							render={
								<Link to="/w/$workspaceSlug/admin/practices/reviews" params={{ workspaceSlug }} />
							}
						>
							<Workflow />
							<span>Practice reviews</span>
						</SidebarMenuSubButton>
					</SidebarMenuSubItem>
				</AdminNavSection>
				<AdminNavSection
					label="Integrations"
					icon={<PlugZapIcon />}
					active={onIntegrationsSection}
					open={integrationsOpen}
					onOpenChange={setIntegrationsOpen}
					collapsed={!isMobile && sidebarState === "collapsed"}
					landingLink={
						<Link
							to="/w/$workspaceSlug/admin/integrations"
							params={{ workspaceSlug }}
							activeOptions={{ exact: true }}
							aria-current={onIntegrationsOverview ? "page" : undefined}
						/>
					}
				>
					<SidebarMenuSubItem>
						<SidebarMenuSubButton
							isActive={onIntegrationsOverview}
							render={
								<Link
									to="/w/$workspaceSlug/admin/integrations"
									params={{ workspaceSlug }}
									activeOptions={{ exact: true }}
								/>
							}
						>
							<LayoutGridIcon />
							<span>Overview</span>
						</SidebarMenuSubButton>
					</SidebarMenuSubItem>
					{integrationKinds.includes(scmKind) && (
						<SidebarMenuSubItem>
							<SidebarMenuSubButton
								isActive={onIntegrationsScm}
								render={
									<Link to="/w/$workspaceSlug/admin/integrations/scm" params={{ workspaceSlug }} />
								}
							>
								<ScmIcon aria-hidden />
								<span>{scmLabel}</span>
							</SidebarMenuSubButton>
						</SidebarMenuSubItem>
					)}
					{integrationKinds.includes("SLACK") && (
						<SidebarMenuSubItem>
							<SidebarMenuSubButton
								isActive={onIntegrationsSlack}
								render={
									<Link
										to="/w/$workspaceSlug/admin/integrations/slack"
										params={{ workspaceSlug }}
									/>
								}
							>
								<SlackIcon aria-hidden />
								<span>Slack</span>
							</SidebarMenuSubButton>
						</SidebarMenuSubItem>
					)}
					{integrationKinds.includes("OUTLINE") && (
						<SidebarMenuSubItem>
							<SidebarMenuSubButton
								isActive={onIntegrationsOutline}
								render={
									<Link
										to="/w/$workspaceSlug/admin/integrations/outline"
										params={{ workspaceSlug }}
									/>
								}
							>
								<OutlineIcon aria-hidden />
								<span>Outline</span>
							</SidebarMenuSubButton>
						</SidebarMenuSubItem>
					)}
				</AdminNavSection>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip={ADMIN_NAV_LABELS.models}
						isActive={onModels}
						render={<Link to="/w/$workspaceSlug/admin/models" params={{ workspaceSlug }} />}
					>
						<BrainCircuit />
						<span>{ADMIN_NAV_LABELS.models}</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="What this workspace spent on AI"
						isActive={onUsage}
						render={<Link to="/w/$workspaceSlug/admin/usage" params={{ workspaceSlug }} />}
					>
						<CircleDollarSign />
						<span>{ADMIN_NAV_LABELS.usage}</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Settings changes in this workspace"
						isActive={onAudit}
						render={
							<Link to="/w/$workspaceSlug/admin/audit" params={{ workspaceSlug }} search={{}} />
						}
					>
						<ScrollText />
						<span>{ADMIN_NAV_LABELS.audit}</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</SidebarGroup>
	);
}

interface AdminNavSectionProps {
	label: string;
	icon: ReactNode;
	active: boolean;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	collapsed: boolean;
	landingLink: ReactElement;
	children: ReactNode;
}

function AdminNavSection({
	label,
	icon,
	active,
	open,
	onOpenChange,
	collapsed,
	landingLink,
	children,
}: AdminNavSectionProps) {
	return (
		<Collapsible open={open} onOpenChange={onOpenChange} render={<SidebarMenuItem />}>
			{collapsed ? (
				<SidebarMenuButton tooltip={label} isActive={active} render={landingLink}>
					{icon}
					<span>{label}</span>
				</SidebarMenuButton>
			) : (
				<CollapsibleTrigger
					render={<SidebarMenuButton tooltip={label} isActive={!open && active} />}
				>
					{icon}
					<span>{label}</span>
					<ChevronRight
						className="ml-auto transition-transform group-aria-expanded/menu-button:rotate-90"
						aria-hidden
					/>
				</CollapsibleTrigger>
			)}
			<CollapsibleContent>
				{/* The list carries the section's name: a screen reader jumping by list otherwise
				    announces "list, 3 items" with nothing saying which section it landed in. */}
				<SidebarMenuSub aria-label={label}>{children}</SidebarMenuSub>
			</CollapsibleContent>
		</Collapsible>
	);
}
