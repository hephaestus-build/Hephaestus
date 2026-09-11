import { Link, useMatchRoute } from "@tanstack/react-router";
import {
	BrainCircuit,
	Building2,
	CircleDollarSign,
	ClipboardList,
	Gauge,
	Inbox,
	KeyRound,
	LibraryBig,
	ScrollText,
	Settings2,
	Users,
} from "lucide-react";

import { ADMIN_NAV_LABELS } from "@/components/core/sidebar/admin-nav-labels";
import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
} from "@/components/ui/sidebar";

import { NavContextHeader } from "./NavContextHeader";

const ADMIN_NAV_GROUPS = [
	{
		label: "Access",
		items: [
			{ to: "/admin/users", label: "Users", icon: Users, tooltip: "Manage accounts" },
			{ to: "/admin/workspaces", label: "Workspaces", icon: Building2, tooltip: "All workspaces" },
			{
				to: "/admin/login-providers",
				label: "Login providers",
				icon: KeyRound,
				tooltip: "Sign-in options",
			},
		],
	},
	{
		label: "Practices",
		items: [
			{
				to: "/admin/catalog",
				label: "Practice catalog",
				icon: LibraryBig,
				tooltip: "What new workspaces receive",
			},
		],
	},
	{
		label: "Product feedback",
		items: [
			{
				to: "/admin/feedback",
				label: "Inbox",
				icon: Inbox,
				tooltip: "Feedback and bug reports",
			},
			{
				to: "/admin/surveys",
				label: "Surveys",
				icon: ClipboardList,
				tooltip: "Publish surveys and read responses",
			},
		],
	},
	{
		label: "AI",
		items: [
			{
				to: "/admin/models",
				label: ADMIN_NAV_LABELS.models,
				icon: BrainCircuit,
				tooltip: "Providers and shared models",
			},
			{
				to: "/admin/usage",
				label: ADMIN_NAV_LABELS.usage,
				icon: CircleDollarSign,
				tooltip: "AI spend and budget caps",
			},
		],
	},
	{
		label: "Operations",
		items: [
			{
				to: "/admin/audit",
				label: ADMIN_NAV_LABELS.audit,
				icon: ScrollText,
				tooltip: "Who did what, and when",
			},
			{
				to: "/admin/settings",
				label: "Instance settings",
				icon: Settings2,
				tooltip: "Silent mode and instance controls",
			},
		],
	},
] as const;

/**
 * The instance-admin (APP_ADMIN) sidebar. Workspace-independent — no workspace switcher — so an
 * admin with zero workspaces can still reach it, and never interleaved with the per-workspace
 * admin nav.
 */
export function NavSuperAdmin() {
	const matchRoute = useMatchRoute();
	return (
		<NavContextHeader title="Back to app" backLink={<Link to="/" className="font-semibold" />}>
			<SidebarGroup>
				<SidebarGroupLabel>Instance administration</SidebarGroupLabel>
				<SidebarMenu>
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip="Instance overview"
							isActive={!!matchRoute({ to: "/admin" })}
							render={<Link to="/admin" />}
						>
							<Gauge />
							<span>Overview</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				</SidebarMenu>
			</SidebarGroup>
			{ADMIN_NAV_GROUPS.map((group) => (
				<SidebarGroup key={group.label}>
					<SidebarGroupLabel>{group.label}</SidebarGroupLabel>
					<SidebarMenu>
						{group.items.map((item) => (
							<SidebarMenuItem key={item.to}>
								<SidebarMenuButton
									tooltip={item.tooltip}
									isActive={!!matchRoute({ to: item.to, fuzzy: true })}
									render={<Link to={item.to} />}
								>
									<item.icon />
									<span>{item.label}</span>
								</SidebarMenuButton>
							</SidebarMenuItem>
						))}
					</SidebarMenu>
				</SidebarGroup>
			))}
		</NavContextHeader>
	);
}
