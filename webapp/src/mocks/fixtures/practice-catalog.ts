import type { CuratedPracticeDefinition } from "@/api/types.gen";

import bundledCatalog from "@bundled-practices";

import {
	mockAuthorDeclaredEvidenceValidation,
	mockDocumentReviewFields,
	mockDocumentWorkType,
} from "./practice";

const group = bundledCatalog.groups.find(
	(candidate) => candidate.slug === "decisions-and-documentation",
);
const practice = group?.practices.find(
	(candidate) => candidate.slug === "published-decisions-name-the-alternatives",
);
if (group === undefined || practice === undefined) {
	throw new Error("Bundled document preview practice is missing");
}

export const realPracticeDefinition = {
	name: practice.name,
	artifactKind: mockDocumentWorkType.artifactKind,
	...mockDocumentReviewFields,
	criteria: `${bundledCatalog.criteriaPreambles["docs.document"]}\n\n---\n\n${practice.criteria}`,
	deliveryBehavior: { summaryOnly: false },
	automatedReviewPolicy: mockDocumentWorkType.recommendedPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	whyItMatters: practice.whyItMatters,
	whatGoodLooksLike: practice.whatGoodLooksLike,
	groupSlug: group.slug,
} satisfies CuratedPracticeDefinition;

export const realGroupName = group.name;
export const realGroupSlug = group.slug;
