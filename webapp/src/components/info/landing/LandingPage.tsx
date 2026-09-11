import { LandingCtaSection } from "./LandingCtaSection";
import { LandingFaqSection } from "./LandingFaqSection";
import { LandingFeaturesSection } from "./LandingFeaturesSection";
import { LandingHeroSection } from "./LandingHeroSection";
import { LandingProjectOriginsSection } from "./LandingProjectOriginsSection";

interface LandingPageProps {
	onSignIn: (idpHint: string) => void;
}

export function LandingPage({ onSignIn }: LandingPageProps) {
	return (
		<div className="flex flex-col">
			<LandingHeroSection onSignIn={onSignIn} />
			<LandingFeaturesSection />
			<LandingProjectOriginsSection />
			<LandingFaqSection />
			<LandingCtaSection onSignIn={onSignIn} />
		</div>
	);
}
