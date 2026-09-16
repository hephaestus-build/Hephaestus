import { createFileRoute, Link, redirect } from "@tanstack/react-router";
import { CheckCircleIcon, InfoIcon, XCircleIcon } from "lucide-react";
import { useEffect, useRef } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

interface Search {
	status?: "success" | "error";
	reason?: string;
}

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
		if (!slug) {
			return;
		}
		window.sessionStorage.removeItem("slack-connect-return-slug");
		if (search.status) {
			window.sessionStorage.setItem("slack-connect-result", search.status);
			if (search.reason) {
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

	const failed = status === "error";
	const succeeded = status === "success";
	const Icon = failed ? XCircleIcon : succeeded ? CheckCircleIcon : InfoIcon;
	const iconClass = failed
		? "size-12 text-destructive"
		: succeeded
			? "size-12 text-success"
			: "size-12 text-muted-foreground";
	const title = failed
		? "Connection failed"
		: succeeded
			? "Integration connected"
			: "Nothing to show here";
	return (
		<div className="mx-auto w-full max-w-md">
			<Card>
				<CardContent className="flex flex-col items-center gap-4 py-8">
					<Icon className={iconClass} />
					<div className="text-center">
						<h1 className="text-xl font-semibold">{title}</h1>
						{failed && reason && (
							<p className="mt-2 text-sm wrap-anywhere text-muted-foreground">{reason}</p>
						)}
					</div>
					<Button render={<Link to="/" />}>Return to dashboard</Button>
				</CardContent>
			</Card>
		</div>
	);
}
