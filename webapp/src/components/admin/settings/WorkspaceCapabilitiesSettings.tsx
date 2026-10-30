import { Link } from "@tanstack/react-router";
import { Activity, ChevronRight, Compass } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import { HephIcon } from "@/components/brand/HephIcon";
import { InlineLink } from "@/components/common/InlineLink";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";

export interface WorkspaceCapabilitiesSettingsProps {
	workspaceSlug: string;
	/** Whether practice reviews run here; the switch itself lives with the review settings. */
	practicesEnabled: boolean;
}

/**
 * Where each capability of the workspace is decided. Nothing here is a switch: a second copy of a
 * control that lives on its own settings page would be a second place to disagree with it.
 */
export function WorkspaceCapabilitiesSettings({
	workspaceSlug,
	practicesEnabled,
}: WorkspaceCapabilitiesSettingsProps) {
	return (
		<section aria-labelledby="workspace-capabilities-heading">
			<h2 id="workspace-capabilities-heading" className="text-lg font-semibold">
				Capabilities
			</h2>
			<p className="mb-4 text-sm text-muted-foreground">
				What members get in this workspace, and where each is decided.
			</p>
			<ul className="rounded-xl border bg-card">
				<Capability
					icon={<Activity />}
					title="Activity"
					description={
						<>
							Always on. Every member sees their own activity and the workspace’s. To hide someone
							or show them again, use{" "}
							<InlineLink
								render={<Link to="/w/$workspaceSlug/admin/members" params={{ workspaceSlug }} />}
							>
								Members
							</InlineLink>{" "}
							for members,{" "}
							<InlineLink
								render={<Link to="/w/$workspaceSlug/admin/teams" params={{ workspaceSlug }} />}
							>
								Team setup
							</InlineLink>{" "}
							for teams and repositories, and{" "}
							<InlineLink
								render={
									<Link
										to="/w/$workspaceSlug/admin/settings"
										params={{ workspaceSlug }}
										hash="hidden-people-heading"
									/>
								}
							>
								Hidden people
							</InlineLink>{" "}
							for people without a membership.
						</>
					}
				/>
				<Capability
					icon={<Compass />}
					title="Practice reviews"
					description={
						practicesEnabled
							? "On. Hephaestus reviews work against the practices this workspace adopted."
							: "Off. Turn them on after the workspace adopts practices and you set up a review model."
					}
					action={
						<CapabilityLink
							link={
								<Link to="/w/$workspaceSlug/admin/practices/review" params={{ workspaceSlug }} />
							}
						>
							Review settings
						</CapabilityLink>
					}
				/>
				<Capability
					icon={<HephIcon />}
					title="Ask Hephaestus"
					description="Every member can ask Hephaestus once you set up a conversation model. Each member’s AI choice still applies."
					action={
						<CapabilityLink
							link={<Link to="/w/$workspaceSlug/admin/models" params={{ workspaceSlug }} />}
						>
							AI models
						</CapabilityLink>
					}
				/>
			</ul>
		</section>
	);
}

function Capability({
	icon,
	title,
	description,
	action,
}: {
	icon: ReactNode;
	title: string;
	description: ReactNode;
	action?: ReactNode;
}) {
	return (
		<Item render={<li />} variant="row">
			<ItemMedia variant="icon" className="text-muted-foreground" aria-hidden>
				{icon}
			</ItemMedia>
			<ItemContent>
				<ItemTitle>{title}</ItemTitle>
				<ItemDescription className="line-clamp-none">{description}</ItemDescription>
			</ItemContent>
			{action !== undefined && <ItemActions>{action}</ItemActions>}
		</Item>
	);
}

function CapabilityLink({ link, children }: { link: ReactElement; children: string }) {
	return (
		<InlineLink
			render={link}
			className="inline-flex items-center gap-1 text-sm font-medium whitespace-nowrap"
		>
			{children}
			<ChevronRight className="size-3.5" aria-hidden />
		</InlineLink>
	);
}
