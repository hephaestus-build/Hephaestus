import { Link, useMatchRoute } from "@tanstack/react-router";
import { ChevronRight } from "lucide-react";

import { HEPH_LABEL, HephIcon } from "@/components/brand/HephIcon";
import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
} from "@/components/ui/sidebar";

export function NavMentor({
	workspaceSlug,
	readOnly = false,
}: {
	workspaceSlug: string;
	readOnly?: boolean;
}) {
	const matchRoute = useMatchRoute();
	const onMentor = Boolean(matchRoute({ to: "/w/$workspaceSlug/conversation", fuzzy: true }));

	return (
		<SidebarGroup>
			<SidebarGroupLabel>Conversations</SidebarGroupLabel>
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip={readOnly ? "Conversation" : HEPH_LABEL}
						isActive={onMentor}
						render={
							<Link
								to="/w/$workspaceSlug/conversation"
								params={{ workspaceSlug }}
								className="group/mentor items-center gap-2"
							/>
						}
					>
						<HephIcon className="-mx-1" size={28} pad={5} strokeWidth={1.5} />
						<span className="min-w-0 flex-1 truncate">
							{readOnly ? "Conversation" : "Ask Hephaestus"}
						</span>
						<div className="ms-auto flex shrink-0 justify-end">
							<ChevronRight className="h-4 w-4 opacity-0 transition-opacity group-hover/mentor:opacity-100" />
						</div>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</SidebarGroup>
	);
}
