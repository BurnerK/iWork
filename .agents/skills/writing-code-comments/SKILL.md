---
name: writing-code-comments
description: Use before writing or editing ANY comment, docstring, docblock, JSDoc, or file header, in any language. Governs whether a comment is worth writing at all, what it should say, and how it is worded. Applies to comments written incidentally while adding a function, fixing a bug, or writing a test, not only when the user asks for comments. Load it before writing the first comment rather than as a cleanup pass, because the default it sets is no comment, and a comment already on the page reads as though it earned its place.
when_to_use: Writing a new function, class, or test that will carry a docblock or an explanatory line. Editing code next to an existing comment. Asked to "add comments", "document this", "explain this code", "add docstrings", or "write a header block". Reviewing or cleaning up comments someone else wrote. Any diff that adds or edits a comment in any language, whatever the original task was.
---

# Writing Code Comments

## When This Applies

Any diff that adds or edits a comment, whatever the task was. Writing a function that gets a docblock counts. Adding a test with a one-line explanation counts. Correcting a comment next to the line you changed counts.

There is no "it is only one short comment" exemption and no "I already know how to comment" exemption. The default set below is no comment, and that is not the default you arrive at on your own. The urge to explain a method you have just written is the specific instinct this skill exists to overrule.

Nor are these the rules you would infer from the file in front of you. Matching the local style is the wrong move twice over: a file dense with restatement teaches you to restate, and the em dash is banned even where the file is full of them.

Load this before writing the comment, not after. A review pass can fix the wording of a comment but rarely deletes it, because a comment already on the page reads as though it earned its place.

## Overview

A comment earns its place by carrying information the code cannot. The reader can already see what the code does; they are reading your comment to learn something the syntax has no way to record.

Default: no comment. Add one when a specific fact below is missing from the code.

## Never Delete: Comments That Are Code

Some comments are read by a tool, not a person: `@ORM\Column`, `# noqa`, `//go:build`, `# rubocop:disable`, `# type:`, `// @ts-expect-error`, `/* eslint-disable */`, `# frozen_string_literal: true`, shebangs, `#pragma`, SPDX headers, `// Code generated ... DO NOT EDIT.`

**They are not comments for the purposes of this skill.** The cover test does not apply to them. Neither does "ruthless", "aggressive", or "strip everything that does not earn its place".

Would removing this line change what any tool does? If yes, keep it. If you are not sure, keep it. Keeping a directive you did not need costs nothing; deleting one breaks a build or silently changes behaviour.

That list is not exhaustive. Treat any comment shaped like `tool:action` or `key: value` as a directive until you have confirmed it is prose. Paired directives move together: deleting a `disable` and its matching `enable` re-enables a rule someone turned off on purpose.

## The Only Four Things Worth Writing

1. **Why this and not the obvious alternative.** The approach a competent reader would expect, and the reason it isn't here.
2. **A constraint from outside this file.** Wire-format, hardware quirk, upstream bug, legal requirement, a caller that depends on this exact behavior.
3. **A hazard the code doesn't advertise.** What silently breaks if someone changes this. The assumption a future edit will violate.
4. **A pointer.** Ticket, RFC section, paper, formula, commit.

If a comment isn't one of these four, delete it.

## The Cover Test

Cover the code. Read only the comment. Did you learn a fact you could not have recovered by uncovering the code?

No → delete it. Not shorten it. Delete it.

```python
# restatement: fails the test
self.timeout = timeout        # timeout in seconds
rows = cursor.fetchall()      # fetch all rows
return sorted(items)          # return the sorted items

# carries information: passes
time.sleep(delay + random.random())  # jitter, or all 200 workers retry on the same tick
if abs(a - b) < 1e-9:                # payments are floats upstream; == misses by one ULP
headers.pop("Authorization")         # requests keeps this across 301s to other hosts
```

## Density

Comments cluster on the surprising 10% and vanish from the rest. Even spacing is the tell. Real code has long uncommented stretches punctuated by a note where something bit someone.

If you've written a comment for each block, you've written none. Never annotate: constructor assignments, parameter glossaries repeating the parameter names, a class header restating the class name, obvious loop and branch conditions.

## Register

Match the file you're in. Read the comments already there and write those.

With no local convention: a lowercase fragment is normal, terminal punctuation optional. Prefer the concrete noun: `bucket-1`, `Safari 16`, `RFC 5322 §3.4` beat "the previous value", "some browsers", "the spec". One clause is a complete comment; three sentences is a lot and needs a reason.

**Never write `—`.** Not in a comment, docstring, commit message, YAML `description`, or Markdown doc, and not even when the surrounding file uses them. Use a full stop between two clauses, a colon before a reason or list, parentheses or commas for an aside, `–` or "to" for a range. Rewrite the sentence rather than swapping the character: a clause that wanted an em dash usually wanted to be two sentences. In a YAML single-line `description:`, avoid the colon too, since an unquoted scalar cannot contain `: `.

## Docstrings

Same test, applied to the contract rather than the lines. Worth stating: units, ownership, mutation, error behavior, thread-safety, invalid ranges. Not worth stating: a prose paraphrase of the signature.

```python
def parse_duration(text):
    """Parse "1h30m" into seconds.

    Returns None on malformed input rather than raising; callers are
    parsing user config and treat None as "use the default".
    Months are rejected: ambiguous length.
    """
```

## Editing Existing Code

Comment only what your change made non-obvious. Leave surrounding comments alone, including bad ones. Rewriting them buries your actual diff. Correct a neighbour only when your change made it false: a comment that now lies is a bug.

## Red Flags

- About to delete a comment containing a colon and a tool name. Check the directive rule above.
- You wrote the comment by reading the line below it and rephrasing.
- The comment would still be true if the logic were inverted.
- Your comment count scales with line count.
