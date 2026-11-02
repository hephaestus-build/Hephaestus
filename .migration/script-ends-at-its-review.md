#### 🔴 Check practice scripts that change a result after `ctx.review`

A practice script now ends when it calls `ctx.review`. Code after that call does not run, and the review's result is the practice's result.

This changes nothing for a script that returns `ctx.review()`. Every script in the shipped catalog does.

If you wrote a practice script that reads or changes the result after the call, or catches a failed review:

1. Open the practice in the practice editor.
2. Move that logic before `ctx.review`, or give it to the review as `directions`.
