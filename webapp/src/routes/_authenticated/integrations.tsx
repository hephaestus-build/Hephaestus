import { createFileRoute, Link, redirect } from "@tanstack/react-router";
import { CheckCircleIcon, InfoIcon, XCircleIcon } from "lucide-react";
import { useEffect, useRef } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { hasText } from "@/lib/text";

interface Search {
	status?: "success" | "error";
	reason?: string;
}

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
	validateSearch: (search): Search => ({
		status: search.status === "success" || search.status === "error" ? search.status : undefined,
		reason: typeof search.reason === "string" ? search.reason : undefined,
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
			if (hasText(search.reason)) {
				window.sessionStorage.setItem("slack-connect-reason", search.reason);
			}
		}
		throw redirect({
			to: "/w/$workspaceSlug/admin/integrations/slack",
			params: { workspaceSlug: slug },
		});
	},
});

function IntegrationsCallback() {
	const { status, reason } = Route.useSearch();
	const toasted = useRef(false);

	useEffect(() => {
		if (toasted.current) {
			return;
		}
		toasted.current = true;
		if (status === "success") {
			toast.success("Integration connected");
		} else if (status === "error") {
			toast.error("Integration connection failed", { description: reason });
		}
	}, [status, reason]);

	const { Icon, iconClass, title } = OUTCOMES[status ?? "none"];
	return (
		<div className="mx-auto w-full max-w-md">
			<Card>
				<CardContent className="flex flex-col items-center gap-4 py-8">
					<Icon className={iconClass} />
					<div className="text-center">
						<h1 className="text-xl font-semibold">{title}</h1>
						{status === "error" && hasText(reason) && (
							<p className="mt-2 text-sm wrap-anywhere text-muted-foreground">{reason}</p>
						)}
					</div>
					<Button render={<Link to="/" />}>Return to dashboard</Button>
				</CardContent>
			</Card>
		</div>
	);
}
