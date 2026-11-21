import { z } from "zod";

// The shipped page builds every schema without compiled parsers (`index.html`), so the tests do too. A setup file runs
// before the test file imports any schema module.
z.config({ jitless: true });
