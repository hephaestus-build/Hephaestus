import { createFileRoute, Link, redirect } from "@tanstack/react-router";
import { CheckCircleIcon, InfoIcon, XCircleIcon } from "lucide-react";
import { useEffect, useRef } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { hasText } from "@/lib/text";

/** What the page shows for each outcome the provider sent back — or for a visit with none. */
const OUTCOMES = {
	success: {
		Icon: CheckCircleIcon,
		iconClass: "size-12 text-success",
		title: "Integration connected",
	},
	error: { Icon: XCircleIcon, iconClass: "size-12 text-destructive", title: "Connection failed" },
	none: {
		Icon: InfoIcon,
		iconClass: "size-12 text-muted-foreground",
		title: "Nothing to show here",
	},
};

export const Route = createFileRoute("/_authenticated/integrations")({
	component: IntegrationsCallback,
	// The server's failure redirect: `reason` is a code, `description` the sentence written for the user.
	validateSearch: z.object({
		status: z.enum(["success", "error"]).optional().catch(undefined),
		reason: z.string().optional().catch(undefined),
		description: z.string().optional().catch(undefined),
	}),
	beforeLoad: ({ search }) => {
		if (typeof window === "undefined") {
			return;
		}
		const slug = window.sessionStorage.getItem("slack-connect-return-slug");
		if (!hasText(slug)) {
			return;
		}
		window.sessionStorage.removeItem("slack-connect-return-slug");
		if (search.status) {
			window.sessionStorage.setItem("slack-connect-result", search.status);
			const detail = failureDetail(search);
			if (hasText(detail)) {
				window.sessionStorage.setItem("slack-connect-reason", detail);
			}
		}
		throw redirect({
			to: "/w/$workspaceSlug/admin/integrations/slack",
			params: { workspaceSlug: slug },
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
			toast.error("Integration connection failed", { description: detail });
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
						Return to dashboard
					</Button>
				</CardContent>
			</Card>
		</div>
	);
}
