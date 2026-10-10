import { createFileRoute, Link, redirect } from "@tanstack/react-router";
import { CheckCircleIcon, InfoIcon, XCircleIcon } from "lucide-react";
import { useEffect, useRef } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { pageHead } from "@/lib/page-title";
import { hasText } from "@/lib/text";

/** What the page shows for each outcome the provider sent back — or for a visit with none. */
const OUTCOMES = {
	success: {
		Icon: CheckCircleIcon,
		iconClass: "size-12 text-success",
		title: "Integration connected",
	},
	error: {
		Icon: XCircleIcon,
		iconClass: "size-12 text-destructive",
		title: "We could not connect the integration",
	},
	none: {
		Icon: InfoIcon,
		iconClass: "size-12 text-muted-foreground",
		title: "Open an integration from workspace administration",
	},
};

export const Route = createFileRoute("/_authenticated/integrations")({
	head: pageHead("Connecting an integration"),
	component: IntegrationsCallback,
	// The server's failure redirect: `reason` is a code, `description` the sentence written for the user.
	validateSearch: z.object({
		status: z.enum(["success", "error"]).optional().catch(undefined),
		reason: z.string().optional().catch(undefined),
		description: z.string().optional().catch(undefined),
		workspaceSlug: z
			.string()
			.regex(/^(?!.*--)[a-z0-9][a-z0-9-]{1,49}[a-z0-9]$/u)
			.optional()
			.catch(undefined),
	}),
	beforeLoad: ({ search }) => {
		if (typeof window === "undefined") {
			return;
		}
		const slug = search.workspaceSlug;
		if (slug === undefined) {
			return;
		}
		throw redirect({
			to: "/w/$workspaceSlug/admin/integrations/slack",
			params: { workspaceSlug: slug },
			search: { status: search.status, reason: search.reason, description: search.description },
		});
	},
});

function failureDetail({ reason, description }: { reason?: string; description?: string }) {
	return hasText(description) ? description : reason;
}

function IntegrationsCallback() {
	const search = Route.useSearch();
	const { status } = search;
	const detail = failureDetail(search);
	const toasted = useRef(false);

	useEffect(() => {
		if (toasted.current) {
			return;
		}
		toasted.current = true;
		if (status === "success") {
			toast.success("Integration connected");
		} else if (status === "error") {
			toast.error("We could not connect the integration", { description: detail });
		}
	}, [status, detail]);

	const { Icon, iconClass, title } = OUTCOMES[status ?? "none"];
	return (
		<div className="mx-auto w-full max-w-md">
			<Card>
				<CardContent className="flex flex-col items-center gap-4 py-8">
					<Icon className={iconClass} />
					<div className="text-center">
						<h1 className="text-xl font-semibold">{title}</h1>
						{status === "error" && hasText(detail) && (
							<p className="mt-2 text-sm wrap-anywhere text-muted-foreground">{detail}</p>
						)}
					</div>
					<Button nativeButton={false} render={<Link to="/" />}>
						Back to dashboard
					</Button>
				</CardContent>
			</Card>
		</div>
	);
}
