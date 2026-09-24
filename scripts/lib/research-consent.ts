export function researchConsentFields(researchOrganization: unknown) {
	return typeof researchOrganization === "string"
		? { participateInResearch: false, researchOrganization }
		: {};
}
