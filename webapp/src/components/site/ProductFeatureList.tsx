import type { ProductFeature } from "./product-features";

interface ProductFeatureListProps {
	features: ProductFeature[];
}

export function ProductFeatureList({ features }: ProductFeatureListProps) {
	return (
		<ul className="grid grid-cols-1 gap-x-8 gap-y-6 sm:grid-cols-2 lg:grid-cols-3">
			{features.map(({ icon: Icon, name, text }) => (
				<li key={name} className="border-t border-border pt-5">
					<div className="flex items-center gap-3">
						<span
							className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-highlight/10 text-highlight"
							aria-hidden="true"
						>
							<Icon className="size-4.5" />
						</span>
						<h3 className="text-base font-semibold">{name}</h3>
					</div>
					<p className="mt-3 text-sm leading-relaxed text-pretty text-muted-foreground">{text}</p>
				</li>
			))}
		</ul>
	);
}
