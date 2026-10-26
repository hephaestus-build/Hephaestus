#### 🔴 Custom practice scripts return results without coverage

Hephaestus now measures the coverage of every practice script result. A custom script that returns a result with a `coverage` field stops as an invalid result, and its practice records no observation.

Before you upgrade, remove `coverage` from each result that your custom scripts build in code. Read in full each source that the result cites: a citation alone shows only the quoted lines. Scripts that return what `ctx.review` returned need no change. The bundled practices need no change.
