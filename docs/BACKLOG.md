# Backlog — wanted, not yet done

Work that is **intended** but not built. The answer here is *"yes, eventually"*.

**This is not `docs/DECISIONS.md`, and the two are easy to confuse.** `DECISIONS.md` holds items
that were **costed and consciously declined** — the answer there is *"no, and here is why"*, and
re-deriving that reasoning is the waste it exists to prevent. Nothing in this file has been
declined; nothing in that one is pending.

Items move **both ways**. Backlog → decisions, when something here is costed and turns out not to
be worth it. Decisions → backlog, when the thing that "would change the answer" actually happens —
each entry over there names its own trigger. Where an item's origin is known, it is stated below.

Ordered by **value**, which here means: how much it reduces the chance of a silently wrong result
or a silently stale claim, weighed against what it costs to build. Age is not a factor — the
oldest entry here, **"Producer-side gaps in Tool 1"**, still does not head the list. Items
touching production code rank above documentation, because only they can produce a wrong answer
for a user.

**The numbers below are positions, not identities.** Because the list is re-ranked whenever an
item is added or completed, a number records only where an item sat at the moment someone wrote
it down. **Reading an older reference:** a changelog row citing "backlog item N" means the item
that held position N *when that row was written* — it may since have moved, or been completed and
removed, and the row is never edited to catch up. **Writing a new one:** cite a backlog item by
**name**, with the number as a convenience at most, so the reference still resolves after the next
re-ranking. p10.24's row is the pattern to copy: it says "now **backlog item 7**" *and* names
`project.build.outputTimestamp` in the same clause, so it stays unambiguous whatever the number
does. That number survived p10.30's re-ranking unchanged and **p10.31 moved it to 8** — the rule
demonstrating itself, not an argument against citing numbers at all.

---

### 1. An automated check that the gotcha index maps onto `GOTCHAS.md`

`CLAUDE.md` carries a one-line index routing to every entry in `docs/GOTCHAS.md`. p10.20 verified
the correspondence **by hand** in both directions — every index link resolves to a heading that
exists, every heading has at least one index line.

**Why:** a drifted index is a *confidently wrong pointer* — it sends a session to an entry that
does not exist, or silently omits one that does, which is the failure class this project refuses
everywhere else. Nothing prevents drift today; the next entry added without an index line breaks
it silently.

**Cost:** small. Parse `###` headings out of `GOTCHAS.md`, parse `docs/GOTCHAS.md#anchor` links
out of `CLAUDE.md`, compare both directions. The awkward part is where it runs — this repo has no
CI, so it would be a test, a script, or a build step.

**It must allow a heading to have MORE than one index line.** The frame-offset entry is
deliberately dual-routed, appearing under both "Dialogs & frame alignment" and "Tools 2/3 —
sampling & extraction" because it genuinely serves both triggers. A one-to-one check would fail on
correct content.

**Urgent when:** a session ever follows an index line to a heading that is not there.

### 2. Producer-side gaps in Tool 1

Two checks Tool 1 does not make, both of which let it emit output that Tool 2 later refuses or
misreads:

- **No cross-timepoint dimension check on output.** Tool 1 skips a timepoint whose *input* size
  differs, but nothing verifies the z-origin TIFFs it writes are mutually consistent.
- **No warning when emitting digit-less output filenames.** Tool 1 refuses digit-less *input*
  names since p10.9, but a dataset can still produce output names that `TiffStackLoader` rejects
  (16-bit) or silently misreads as frame 32 (32-bit, from the `32bit` in the name).

**Why:** the producer knows at write time what the consumer will do with the name; discovering it
one tool later is strictly worse.

**Cost:** moderate, and **this is the only item here that touches production code** — so unlike
every patch since p10.14 it needs a **manual Fiji run** before push, and it moves
`<patch.version>`.

**Urgent when:** anyone hits the p10.9 failure from the producer side rather than the consumer
side.

### 3. Carve NaN out of any whole-image validation the TopoJ loader gains

`TopoJZConversion.classify` returns **`INVALID` for NaN**, which is correct in isolation: NaN is
neither sentinel nor a point on the encoding lattice. But Tool 3 today treats a NaN pixel as
**no data** — `TopoJStackLoader` keeps it un-rounded, `ZAggregator` filters it out, and a detection
whose sampled pixels are *all* NaN reports `STATUS_NO_DATA`, which is a per-detection outcome, not
a reason to refuse the file.

**Why:** the two readings collide the moment the loader gains whole-image validation — the natural
next use of the conversion class, and already costed as cheap because the loader holds every frame
in memory anyway. A rule shaped like *"refuse the file if any pixel classifies INVALID"* would
reject every legitimately NaN-bearing float map, all of which load today. That is a **behaviour
regression**, and its loudness is not a defence: the user gets a file they have always been able to
open now refused outright, with no way to tell it from a genuinely malformed one.

**Cost:** effectively nil *if it is remembered* — one branch in whatever validation gets written.
The entire cost sits in not remembering, which is why this is recorded before the validation
exists rather than after it ships.

**Urgent when:** anyone writes whole-image validation into `TopoJStackLoader`, or promotes
`TopoJZConversion.ValueClass.INVALID` into a file-level refusal anywhere else.

### 4. `@Tag` grouping to separate I/O-free tests from real TIFF I/O

The suite mixes pure-logic tests with tests doing real headless TIFF reads and writes. Splitting
them with JUnit 5 `@Tag` would let a session run the fast ones alone.

**Why:** flagged repeatedly and never taken — at p10.14 and again at p10.19. The case got stronger
at p10.14: `ProjectionStackScannerMemoryTest` writes **~100 MiB to a temp directory on every
run**, which is the single largest cost in the suite and is pure I/O.

**Cost:** small but spread wide — a tag on each I/O-touching class, plus Surefire configuration
for the groups. The complication is the existing **two-execution** split (see `docs/TESTS.md`):
tags would have to compose with it rather than replace it, and collapsing that split would delete
the streaming guard.

**Urgent when:** the suite gets slow enough that people skip it — which the rule requiring a green
suite before `mvn install` makes costly.

### 5. Run the demos in the build and fail on any diff

*(Moved here from `docs/TESTS.md`, reasoning intact.)*

**Run the demos in the build and fail on any diff against their committed snapshot.** Now that the
output is deterministic this is possible, and it would turn the snapshots into a real regression
guard rather than a manually-maintained artifact. Note the useful form is **fail-on-diff, not
auto-regenerate**: a snapshot that the build silently overwrites to match whatever the code now
does has stopped being evidence of anything, which is the opposite of what it is for. Deferred
because it is a new guarantee rather than a cleanup.

**Cost:** moderate. The four demos already run deterministically (p10.19), so the work is a runner
that executes each, captures stdout with the UTF-8 flags, and diffs against the committed file.
Interacts with **"`@Tag` grouping to separate I/O-free tests from real TIFF I/O"** (item 4 as
this is written): these are I/O-heavy and would want their own group.

**Urgent when:** a demo snapshot is found stale in a way that hid a real behaviour change — the
p10.19 regeneration found exactly that, a `noData=` counter added at p10.13 and never reflected.

### 6. A one-sentence summary at the head of every new changelog row

Rows in `docs/CHANGELOG.md` open straight into their reasoning. That is what makes them worth
keeping, but it means finding the row that explains a given behaviour requires reading into each
one — and the rows have grown, the p10.29 row being the longest yet.

**Why:** the changelog is the artifact the workflow leans on every patch, and a record nobody can
scan is one people stop consulting. A leading sentence stating *what changed* would let the file
be skimmed while the reasoning stays exactly where it is.

**Cost:** effectively nil, and it is a **convention rather than a change** — one sentence at the
head of each new row, written as the row is written.

**Prospective only.** Rows are never edited, so this applies to new rows and makes **no claim
about existing ones**; the file will be mixed for a long time, and that is correct rather than
untidy. Do not retrofit.

**Urgent when:** someone needs a row they know exists and cannot find it.

### 7. README reorganisation

README still opens with developer-only material — project structure, build steps, prerequisites —
before anything a user of the plugin needs.

**Why:** the survey is already recorded in **p10.22's changelog row**, with section-by-section line
ranges and word counts. Cited rather than restated here deliberately: those ranges drift with every
edit, and a copy here would be wrong within a patch or two.

**Cost:** moderate, and mostly judgement rather than typing — three sections are genuinely mixed
(user-relevant content written in internal class names) and need rewriting, not just moving.

**Urgent when:** a colleague is handed README as the way to learn the plugin.

---

### 8. Set `project.build.outputTimestamp` to make the build reproducible

`pom.xml` does not set `project.build.outputTimestamp`, so Maven stamps every ZIP entry with the
moment it was written. Two `mvn clean package` runs over unchanged source therefore produce JARs
with **different SHA-256 hashes** — measured, at identical size and with all 56 class files inside
byte-identical.

**What it would buy:** JAR hashes would become meaningful. Today they answer no useful question —
see [Deciding whether executable content changed](GOTCHAS.md#deciding-whether-executable-content-changed) —
so "is the deployed JAR the one I just built?" can only be answered by hashing both *right now*,
not by comparing against a recorded value.

**Cost:** small mechanically — one property, conventionally pinned to the last commit's timestamp.
The consequences are what need thought: the value has to be maintained or derived, and a
deliberately-frozen `<patch.version>` across several patches (which is normal here) would then
produce JARs that differ only in that stamp, which may be more confusing than the present
situation rather than less.

**It fixes only half the hazard.** The class-level half — a javadoc edit shifting every
`LineNumberTable` entry — is inherent and no build setting removes it, so the instruction-level
`javap` check stays necessary either way.

**Urgent when:** anyone needs to verify a deployed JAR against a recorded hash rather than a
freshly built one.

### 9. Audit the "byte-for-byte unchanged" freeze claims

Several places claim a file or path was left untouched. Checked against `git log` while writing
this entry, which shrank it considerably:

| Claim subject | Commits since | State |
|---|---|---|
| `TiffStackLoader` | 5 — p9.1, p10.1, p10.2, p10.4, p10.12 | **Already handled** — the `GOTCHAS.md` claim is scoped, not stale |
| `ZSampler` | 2 — p9.1 (package move), p10.13 (radius clamp) | **The real remaining work** |
| `ZTrackerDialog` | 1 — p9.1 (package move only) | **Clean** — needs a confirming glance, nothing more |

**The motivating case is already closed.** `TiffStackLoader` is the file this audit was proposed
for, and its claim was found stale and dealt with correctly: `GOTCHAS.md` **scopes** it rather than
deleting it — *"it is **superseded** as a statement about the present … What survives is the rule
the exception established."* **That is the precedent for whatever is left** — these claims record
a design intent worth keeping, so the fix is to date them, not remove them.

**What actually remains** is one substantive check (`ZSampler`, which p10.13's radius clamp
changed) and one confirmation (`ZTrackerDialog`, untouched apart from a package move, so its claim
still holds in substance).

**Why:** a freeze claim that is no longer true invites someone to rely on it when judging whether
a change is safe.

**Cost:** small, and mostly reading — the claims sit in `CLAUDE.md`'s Tool 2/Tool 3 isolation notes
and `README.md`'s project-structure tree.

**Urgent when:** someone treats one of the two remaining claims as licence to skip checking.

### 10. `build-guide.mjs` support for a deeper heading level

The user guide is structurally **flat** — every section is an `h2`, including the three that
belong under Part 1 — because the generator caps headings at three levels.

**Why:** flat was chosen at p10.29 only because nesting was unavailable, not because it is right.
The cap is also a **trap**: `build-guide.mjs` matches headings with `/^(#{1,3})\s/`, so a
fourth-level heading falls through to the paragraph accumulator. Verified by probe during p10.29 —
it leaks literal hashes into the `.html`, `.txt` **and** `.pdf`, and because a paragraph is not a
section boundary it swallows the section inside the preceding step card. That is the p10.3 failure
mode: the parser silently mangling output while the `.md` renders correctly on GitHub.

**Cost:** small but spread across three renderers — the two heading regexes, plus an `h4` case in
the HTML and text paths, plus a decision about whether an `h4` closes an open step card. No test
covers the generator, so it needs careful before/after reading of all three outputs.

**Urgent when:** someone writes a fourth-level heading without knowing the cap exists — the output
is visibly broken rather than silently wrong, but it reaches a colleague-facing PDF.

### 11. `build-guide.mjs` table-cell wrapping for the `.txt`

The text renderer sizes each column to its **longest cell** and never wraps cell content, so one
long cell widens every row in that table.

**Why:** it is the only place the `.txt` exceeds its 72-column budget. **Currently a
nice-to-have, not a fix** — p10.29 moved two over-long cells into prose and brought the widest
line to **397**, below the 401 it inherited, so nothing is presently worse than it was.

**Cost:** moderate, and larger than it looks — wrapping cells means multi-line rows, which means
padding continuation lines and tracking row height, and it changes **every** table in the guide at
once. Same untested-generator caveat as **"`build-guide.mjs` support for a deeper heading level"**.

**Urgent when:** a future row or cell pushes the widest line back above where it started, or the
`.txt` is actually read in a fixed-width terminal rather than kept as a fallback format.

### 12. A progress callback for the whole-stack TopoJ decode

`TopoJStackDecoder.decode` makes two passes over every pixel — about **12 seconds** on the
362-frame, 636,893,388-pixel reference dataset — and says nothing while it does. The plugin sets
the status line to `Decoding TopoJ values to Z…` before the call, and the progress bar then sits
still, because the decoder makes **no ImageJ calls at all**. It is the only phase of a Tool 3 run
without feedback; the loader drives the bar frame by frame.

**Why:** judged acceptable at p11.0 — the manual Fiji run reported that it reads as working rather
than hung — so this is polish, not a fix.

**Cost:** small, with one property that must survive it. The decoder deliberately holds **no
ImageJ types**, which is what keeps it testable headlessly and keeps a UI dependency out of
`core`. A `java.util.function.IntConsumer` parameter preserves exactly that, being a JDK type: the
plugin passes `f -> IJ.showProgress(f, total)` and tests pass a no-op — the same shape as
`LoadedFloatStack.frameView()` keeping `FrameAligner` free of pixel access. Two details decide the
work: **both** passes must report, or the bar jumps 0 → 50 → 100 and the silent half is still
silent; and it means either a new parameter on `decode` or an overload, and p10.32 ruled out
overloads as convenience surface — so it is a signature change touching existing tests.

**It sits last on purpose.** It cannot produce a wrong answer, which is the axis this list ranks
on, however visible it is.

**Urgent when:** someone runs it on a dataset large enough that the silent window stops reading as
working, or it lands inside a batch loop where the absence of per-item feedback compounds.

## Declined, not backlog — see `docs/DECISIONS.md`

Recorded here only so they are not re-proposed as if they were pending.

- **A test for the compressed-TIFF fallback in `ProjectionStackScanner.openStack`.** Declined for
  want of a known producer: the acquisition software writes uncompressed TIFFs, and that is a
  property of the writer rather than of any one file. **What would move it here:** compressed
  stacks actually entering the pipeline — new microscope, new acquisition software, or a batch job
  run to shrink a dataset on disk. Full reasoning, including what is and is not covered today, is
  in `DECISIONS.md`.
