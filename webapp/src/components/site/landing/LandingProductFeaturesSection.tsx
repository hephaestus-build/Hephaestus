import { PRODUCT_FEATURES } from "@/components/site/product-features";
import { ProductFeatureList } from "@/components/site/ProductFeatureList";

export function LandingProductFeaturesSection() {
	return (
		<section aria-labelledby="landing-product-features-heading" className="w-full py-12 md:py-20">
			<div className="mx-auto w-full max-w-6xl px-4 md:px-6">
				<h2
					id="landing-product-features-heading"
					className="mb-8 text-3xl font-bold tracking-tight text-balance sm:text-4xl"
				>
					Features
				</h2>
				<ProductFeatureList features={PRODUCT_FEATURES} />
			</div>
		</section>
	);
}
