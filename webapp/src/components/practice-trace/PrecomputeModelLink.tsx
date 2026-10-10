import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import type { AgentPurpose } from "@/components/practice-vocabulary/agent-purpose-defs";

export interface PrecomputeModelLinkProps {
	workspaceSlug: string;
	/** The purpose AI models opens and scrolls to. */
	purpose: AgentPurpose;
	children: ReactNode;
}

/** The way to a precompute script's missing or failing model: its purpose on AI models. */
export function PrecomputeModelLink({
	workspaceSlug,
	purpose,
	children,
}: PrecomputeModelLinkProps) {
	return (
		<InlineLink
			render={
				<Link to="/w/$workspaceSlug/admin/models" params={{ workspaceSlug }} search={{ purpose }} />
			}
			className="inline-flex items-center gap-1 font-medium"
		>
			{children}
		</InlineLink>
	);
}
