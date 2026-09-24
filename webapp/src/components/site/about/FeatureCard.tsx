import type { ReactNode } from "react";
import { Badge } from "@/components/ui/badge";

export interface FeatureData {
	icon: ReactNode;
	badge: string;
	title: string;
	description: string;
	content: string;
}

interface FeatureCardProps {
	feature: FeatureData;
}

export function FeatureCard({ feature }: FeatureCardProps) {
	const { icon, badge, title, description, content } = feature;

	return (
		<article className="border-t border-border py-6">
			<div className="mb-5 flex items-center justify-between gap-3">
				<div
					className="flex size-11 items-center justify-center rounded-2xl bg-mentor/10 text-mentor"
					aria-hidden="true"
				>
					{icon}
				</div>
				<Badge variant="secondary">{badge}</Badge>
			</div>
			<h3 className="text-xl font-semibold">{title}</h3>
			<p className="mt-1 text-sm font-medium text-muted-foreground">{description}</p>
			<p className="mt-4 leading-relaxed text-muted-foreground">{content}</p>
		</article>
	);
}
