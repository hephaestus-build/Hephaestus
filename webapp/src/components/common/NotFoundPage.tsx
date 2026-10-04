import { Link } from "@tanstack/react-router";

export function NotFoundPage() {
	return (
		<div className="mx-auto flex w-full max-w-2xl flex-col items-center justify-center py-16 text-center">
			<h1 className="mb-4 text-3xl font-bold">Page not found</h1>
			<p className="mb-8 text-muted-foreground">
				This page does not exist, or you do not have permission to view it.
			</p>
			<Link to="/" className="font-medium text-primary hover:underline">
				Go to home page
			</Link>
		</div>
	);
}
