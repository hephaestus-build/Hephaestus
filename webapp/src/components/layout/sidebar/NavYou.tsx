import { Link, useMatchRoute } from "@tanstack/react-router";
import { Activity, Compass } from "lucide-react";

import { HEPH_LABEL, HephIcon } from "@/components/brand/HephIcon";
import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
} from "@/components/ui/sidebar";

export function NavYou({
	workspaceSlug,
	practicesEnabled,
	showMentor,
	readOnly = false,
}: {
	workspaceSlug: string;
	practicesEnabled: boolean;
	showMentor: boolean;
	readOnly?: boolean;
}) {
	const matchRoute = useMatchRoute();
	const conversationLabel = readOnly ? "Conversation" : HEPH_LABEL;
	return (
		<SidebarGroup>
			<SidebarGroupLabel>You</SidebarGroupLabel>
			<SidebarMenu aria-label="You">
				{practicesEnabled && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip="Practice profile"
							isActive={Boolean(
								matchRoute({ to: "/w/$workspaceSlug/practice-profile", fuzzy: true }),
							)}
							render={<Link to="/w/$workspaceSlug/practice-profile" params={{ workspaceSlug }} />}
						>
							<Compass aria-hidden />
							<span>Practice profile</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Activity"
						isActive={Boolean(matchRoute({ to: "/w/$workspaceSlug/activity", fuzzy: true }))}
						render={<Link to="/w/$workspaceSlug/activity" params={{ workspaceSlug }} />}
					>
						<Activity aria-hidden />
						<span>Activity</span>
					</SidebarMenuButton>
				</SidebarMenuItem>
				{showMentor && (
					<SidebarMenuItem>
						<SidebarMenuButton
							tooltip={conversationLabel}
							isActive={Boolean(matchRoute({ to: "/w/$workspaceSlug/conversation", fuzzy: true }))}
							render={<Link to="/w/$workspaceSlug/conversation" params={{ workspaceSlug }} />}
						>
							<HephIcon size={16} animated={false} />
							<span>{conversationLabel}</span>
						</SidebarMenuButton>
					</SidebarMenuItem>
				)}
			</SidebarMenu>
		</SidebarGroup>
	);
}
