// k6 exposes WebCrypto on the global scope, which `@types/k6` does not declare; only `randomUUID`
// is read here.
declare const crypto: { randomUUID: () => string };
