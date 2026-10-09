## How to do it

- Split by goal, not by file. When a title needs “and”, the work is often two changes.
- Do the groundwork first. Move or rename code in its own change, so the next change shows only the new behavior. Remove the old code after that.
- Keep formatting, lockfile and generated updates apart, or say in the description where they are.
- Split before you ask for a review. A reviewer reads a small change line by line and skims a large one.

![Three changes in order: first a refactor with no new behavior, then the new feature, then a cleanup that removes the old code.](figures/split-order.svg)

## When it does not apply

- The change has nothing to review.
- The change holds only generated or vendored files that a tool can make again, and nothing that a reviewer must read.

A migration, a snapshot or a generated contract still counts when it changes what the software does.

## Common mistakes

- Looking for a line limit. There is none. A long change that does one simple thing can be easy to read. A short change that spreads across unrelated areas can be hard.
- Splitting work into changes that cannot be read alone. Each change should make sense to a reviewer by itself.
- Fixing an unrelated bug while you are there. Open a second change for it.

## Sources

- [Small CLs](https://google.github.io/eng-practices/review/developer/small-cls.html), Google Engineering Practices.
- [Modern code review: a case study at Google](https://research.google/pubs/modern-code-review-a-case-study-at-google/), Sadowski and others, 2018.
- [Characteristics of useful code reviews: an empirical study at Microsoft](https://www.microsoft.com/en-us/research/publication/characteristics-of-useful-code-reviews-an-empirical-study-at-microsoft/), Bosu, Greiler and Bird, 2015. The more files a change touched, the smaller the share of review comments that helped its author.
