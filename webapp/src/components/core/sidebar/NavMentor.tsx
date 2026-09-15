import { Link, useMatchRoute } from "@tanstack/react-router";
import { ChevronRight, Sparkles } from "lucide-react";

import { HephIcon } from "@/components/brand/HephIcon";
import { Badge } from "@/components/ui/badge";
import {
	SidebarGroup,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
} from "@/components/ui/sidebar";

export function NavMentor({ workspaceSlug }: { workspaceSlug: string }) {
	const matchRoute = useMatchRoute();
	const onMentor = Boolean(matchRoute({ to: "/w/$workspaceSlug/mentor", fuzzy: true }));

	return (
		<SidebarGroup>
			<SidebarGroupLabel>Mentor</SidebarGroupLabel>
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton
						tooltip="Heph, AI mentor"
						isActive={onMentor}
						render={
							<Link
								to="/w/$workspaceSlug/mentor"
								params={{ workspaceSlug }}
								className="group/mentor items-center gap-2"
							/>
						}
					>
						<HephIcon className="-mx-1.5" size={30} pad={5} strokeWidth={1.5} />
						<span className="flex items-center gap-2">
							Heph
							<Badge variant="muted">
								<Sparkles className="h-3.5 w-3.5" /> AI mentor
							</Badge>
						</span>
						<div className="flex justify-end w-full">
							<ChevronRight className="h-4 w-4 opacity-0 group-hover/mentor:opacity-100 transition-opacity" />
						</div>
					</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</SidebarGroup>
	);
}
