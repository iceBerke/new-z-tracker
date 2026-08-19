# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Where the documentation lives

| File | Holds | Open it when |
|---|---|---|
| **`CLAUDE.md`** (this file) | Architecture, the patch workflow, and a one-line index into the gotchas | Always — start here |
| **`docs/GOTCHAS.md`** | Bugs that reached working code, with the reasoning behind each fix | The index below matches what you are about to touch |
| **`docs/DECISIONS.md`** | Costed and **declined** — *"no, and here is why"* | Considering something that may already have been ruled out |
| **`docs/BACKLOG.md`** | Wanted but **not done** — *"yes, eventually"* | Picking up work, or deferring something |
| **`docs/TESTS.md`** | Suite index, the demos, fixtures, and the test-count rule | Adding a test, or counting them |
| **`docs/CHANGELOG.md`** | Every patch, newest first. **Rows are never edited** | Asking why something is the way it is |
| **`README.md`** | User-facing: install, pipeline, input requirements, memory | Explaining the plugin to someone who runs it |
| **`docs/ZTracker_User_Guide.md`** | The colleague-facing guide. **Single source** — `node docs/build-guide.mjs` regenerates the `.html`/`.txt`/`.pdf`, which are never hand-edited | Changing anything a non-technical user sees |

## Build, Install & Test

```bash
mvn install
```

Produces `target/z-tracker-v4-pN.n.jar` (a fat JAR via `maven-assembly-plugin`), copies it
automatically to the local Fiji plugins folder, removes any superseded `z-tracker-v4-*.jar`
there, and verifies both steps succeeded. The tools appear under **Plugins > ZTracker**
(in pipeline order: **Z-Projection + Origin Map**, **3D Z-Coordinate Extractor**, and
**3D Z-Extractor (TopoJ / direct-Z)**) after restarting Fiji.

**Auto-deploy path** — machine-specific. To use on a different machine, update the
`<outputDirectory>` of the `copy-to-fiji` execution in `pom.xml`.

### Patch workflow

**Three identifiers, and two of them look alike.** (a) and (b) share the `p10.N` format and are
easy to conflate; they are not the same thing and are currently **not equal**.

| | What it is | When it changes |
|---|---|---|
| **(a) patch number** — e.g. `p10.21` | Identifies the *patch*: the changelog row and the commit subject | **Every patch, without exception** |
| **(b) `<patch.version>`** — `pom.xml` properties, now `p10.14` | The only thing that names the built JAR, via `finalName` `z-tracker-v4-${patch.version}` | **Only when the JAR's executable content changes** |
| **(c) `<version>`** — `1.0.0` | The Maven artifact version, overridden by `finalName` for the JAR | Not part of this workflow; deliberately left alone |

**They diverge, and that is correct.** Patches p10.15 onward all sit at `patch.version p10.14`,
because none changed executable content — the JAR they build would differ in name only, asserting
a difference in the artifact that does not exist. The commit hash is the record instead.

**The test is executable content, not "a file under `src/main` was touched".** p10.17 edited a
javadoc block in `src/main`; the resulting class file was *not* byte-identical (every
`LineNumberTable` entry shifted by the four lines the comment grew), but `javap -c -p` output was
identical line for line. Instructions unchanged ⇒ `patch.version` unchanged. **Do not answer this
question by comparing hashes** — see [Deciding whether executable content changed](docs/GOTCHAS.md#deciding-whether-executable-content-changed).

**What it moves *to* is the current patch number.** That much is established practice — **50 of
the 51 historical moves** set it to the patch number of the commit that changed it. What is new is
moving it after it has *stood still*: p10.15 onward is the first time it has spanned several
patches, so this part is a **decision taken here, not a precedent being described**. When it next
moves it takes the current patch number, **closing the whole gap in one step** — it never steps
through the intervening numbers and keeps no sequence of its own, because the filename's job is to
name the patch whose content is in the JAR, and any other value would name a patch whose content
is not. **JAR filenames are therefore sparse by design:** a gap means those patches produced no
distinct build, not that versions went missing. Sparseness is in fact already visible in the
record — no `p5.x` or `p6.x` JAR exists — though that arrived by accident rather than by rule, and
the single historical mismatch behind it (`ab1f03d`, subject p6.0, setting the version to p7.0) is
unexplained and was deliberately not investigated: it predates the settled practice, as p3.0 does
for the capability rule.

**The six steps.**

1. Make the change.
2. **Report for review** — diffs shown, every claim verified against the repo, mismatches reported
   rather than silently fixed, unrequested scope flagged for accept-or-drop. **Stop here; do not
   commit.**
3. On approval, increment the **patch number** (a).
4. Update **`<patch.version>`** (b) *only if* the JAR's executable content changed.
5. Add the changelog row to `docs/CHANGELOG.md`. **The row must state that patch's own version
   decision** — whether `<patch.version>` moved, and if not, why. That reasoning belongs in the
   row because the row is where someone who finds a run of patches sharing one version will
   actually look; a commit message is not enough. *This is the rule from p10.21 onward — earlier
   rows are inconsistent about it (only p10.15 and p10.20 state it), and rows are never edited, so
   that gap is permanent.* **Draft the row here, after approval — not as part of the changes
   reviewed at step 2 — but show its text alongside the commit message before committing.** A row
   is permanent the moment it lands, so it must not be the one artifact nobody read.
6. Commit, **after showing the proposed commit message for approval** — approving the change at
   step 2 does not approve the wording, and neither does a prompt that specifies in detail what
   the message must cover. **Push only when asked** — push is always a separate step, never
   assumed, and approving the message does not approve the push.

**Commit message shape.** Subject **targets 50 characters, hard maximum 72**; body wrapped at
**72**. The body states **what** changed and routes to the changelog row for **why**, so the two
stop carrying the same reasoning twice — the row is the record, the message is the pointer. This
is a **real change, not a description of practice**: measured over the last 60 commits, subjects
reach **135** characters and bodies wrap at about **85** (86 measured). It is **prospective**,
took effect at **p10.29**, and **no past commit is rewritten** — history stays as it was written,
for the same reason changelog rows do. Subject conventions, which *are* existing practice and
should be kept: imperative, sentence case, no trailing period, and the patch number as a
` — p10.N` suffix; join two clauses with `, and` rather than a semicolon (no subject in the
history uses one).

**Reporting rule.** A completion report must state the outcome of **every** item in the prompt,
including items that went fine. p10.20 is the worked example: four requested italic lines were
implemented correctly but never mentioned, which from outside is indistinguishable from having
been dropped during that patch's mid-flight restart — and resolving the ambiguity cost a full
verification round. **An unmentioned item is not a silent success; it is an unknown.**

**Verify both ways.** Step 2's "every claim verified" is the *before* half: check the prompt's
premises — paths, line numbers, counts, "X is unchanged" — against the actual files, and if one
does not hold, say so and stop rather than proceeding on a silently corrected premise. The *after*
half is the same discipline pointed the other way: once the change is made, **re-run whatever it
could plausibly have disturbed** — the suite, the encoding sweep, cross-link resolution,
committed-row integrity — and report the result **including when nothing moved**. A change that
verified clean on the way in can still break something on the way out, and "no change" is a
finding while silence is not.

**Manual Fiji run** — required before push when the change touches a reachable production path,
and in particular for any UI/AWT change (see the testing note below: that code is manual-only).
Documentation, test, and comment-only changes need none.

**One principle, four artifacts.** Neither the version number nor a changelog row may assert
something untrue. A `<patch.version>` that moves without the JAR changing claims a difference in
the artifact that does not exist; a row revised after the fact claims to record what was
understood at the time when it no longer does. Both edits look like tidying, and both destroy the
record's value as evidence — a version that cannot be trusted to mean anything, and a log that
cannot be trusted to be contemporaneous. `docs/CHANGELOG.md`, `docs/GOTCHAS.md` and
`docs/DECISIONS.md` each state the append-never-rewrite half of this at the top of the file; the
version number is the fourth artifact under the same rule.

**Major versions** — `p10.x` → `p11.0` — are earned by **capability**: a major bump means the
plugin can now do something for a user that it could not do before, such as a new tool, a new
input type, or a new output format. Fixing, hardening, refactoring or documenting what it already
does is a minor, however large the change. The minor then resets to 0 (`p10.20` → `p11.0`), as
every one of the nine major transitions so far has done.

The clearest examples are **p8.0** (added Tool 1, the Z-Projection generator), **p9.0** (added
Tool 3, the TopoJ / direct-Z extractor) and **p10.0** (Tool 1 gained a second input type). Note
this rule describes the convention **from p10.21 onward** and does not explain every historical
boundary — **p3.0**, for instance, was label and description tweaks, which is not a capability;
the convention settled later than the early majors did.

A major follows these same six steps — same approval gate, same changelog row stating its own
version decision, same separate push; step 3 resets the minor to 0 and step 4 always moves
`<patch.version>`, but both follow from the rules above rather than being major-specific
exceptions. That a major always moves `<patch.version>` follows from the executable-content test:
new capability is executable production code. A major also adds a reachable production path, so
the manual Fiji run condition is met and a run is required before push.

**Clean old JARs** — a `maven-antrun-plugin` `clean-old-jars` execution (install phase) *attempts*
to delete any `z-tracker-v4-*.jar` in the Fiji plugins folder **except the current
`<patch.version>`**, so a version bump doesn't leave the previous JAR behind (which would make Fiji
register the plugin twice). It excludes the current version, so it is safe regardless of order
relative to the copy — it can never remove the freshly-built JAR. It runs with
`failonerror="false"` **on purpose**, so one undeletable file doesn't abort the sweep over the
others — which means this step **cannot itself guarantee the outcome**, and `verify-deploy` below
is what enforces it. Note also that Ant logs `[delete] Deleting <file>` as its *intent*: that line
appears even when the delete then fails, so it is **not** proof the file went.

**Build verification** — a second `maven-antrun-plugin` execution (`verify-deploy`) runs after
the copy and fails the build with a clear message if the fat JAR in `target/` is missing, if the
deployed file in the Fiji plugins folder is missing, or — added p10.8 — if **any superseded
`z-tracker-v4-*.jar` is still sitting in the plugins folder**, naming every leftover and telling
you to quit Fiji and re-run. That third check exists because "the new JAR arrived" is not the same
claim as "the old one left": see [Running mvn install with Fiji open](docs/GOTCHAS.md#running-mvn-install-with-fiji-open) for the case that slips through without it.

Most testing is manual: build, install in Fiji, and run the dialog against sample JSON / TIFF /
CSV inputs, inspecting the `.npy`, CSV, and ROI `.zip` outputs. UI/AWT code is manual-only — it
requires a live display and has no testable business logic. Core algorithmic logic
(`ztracker.core`, `ztracker.io`, `ztracker.project`, `ztracker.export`) has a growing JUnit 5
suite under `src/test/java` (test-scoped dependency only — this does not violate the
no-runtime-deps rule below). Run with `mvn test`. Tool 1's suite is `ztracker.project.ZProjectorTest`
(projection logic: min/max selection, ties→first layer, missing-layer global-index remap, and the
p10.11 NaN rule — a NaN never wins, a NaN in a later layer was always harmless and must stay so,
and the all-NaN column pinned at layer 0 as the documented limit, each checked in both modes since
the mechanism is symmetric — all I/O-free), `ztracker.io.projector.ProjectionInputScannerTest` (the input scanner: numeric z-layer
sort with negatives/gaps and non-numeric folders ignored, timepoint-filename union/dedup/sort, and
— the subtle one — recording each present slice's **global** z-index when a timepoint is absent from
some layers; plus `isDataset`/`parseZ`, all via real headless TIFF I/O), and
`ztracker.export.projector.ProjectionExporterTest`, whose **seam test** writes the z-origin
TIFFs + JSON mapping and reads them back through the extractor's own `TiffStackLoader` +
`ZMappingLoader`, proving the project→extract round-trip actually holds (it also uses ImageJ's
real TIFF read/write headlessly, confirming that works in the test JVM).
`ztracker.projector.ZProjectorPluginTest` covers `ZProjectorPlugin.resolveDatasetOutDir` — the
p8.2 output-nesting rule that **single** scope drops the redundant projection-type grouping level
(`<out>/max_z_<dataset>/`) while **batch** keeps it (`<out>/max_z/max_z_<dataset>/`), plus the
`max_z`/`min_z` prefix keeping both projections' folders from colliding when they share one
output folder. It also covers the pure decision helpers the same `processDataset` consults —
`dimensionMismatch` (p10.8: matching size accepted; smaller, larger, and height-only changes all
reported, naming both files and both sizes), `dimensionSkipSummary`, the per-dataset note that
blames the *reference* once the skips are the majority, and the two p10.9 pre-flight refusals —
`missingTimepointIndexError` (digits anywhere suffice, every offender listed at once, the missing
index stated before any mention of the extractor, and no depth arguments at all) and
`duplicateTimepointIndexError` (the p10.4 collision caught at scan time; last digit run not first,
numeric grouping so mixed padding widths stay distinct while `7`/`0007` collide, only colliding
names listed, and digit-free names left to the other check rather than collapsed together).
**Four behaviours of `processDataset` are deliberately NOT covered** — the accounting around a
dimension-skipped timepoint, all of it p10.6's exact failure mode. They are unreachable from a
test without widening two visibilities, which was costed and declined; the entry in
`docs/DECISIONS.md` names all four and says why. **The only thing that exercises them is a manual
Fiji run**, so re-verify them by hand when changing that loop.
Tool 1's second input type (per-timepoint TIFF stacks, p10.0) adds
`ztracker.io.projector.ProjectionStackScannerTest` (Z parsed from real slice labels including
every rejection case, ascending-Z sort with slices stored out of order in the file, numeric
timepoint ordering across padding widths, partial-layer timepoints keeping their global indices,
**unsigned** 8-/16-bit and exact 32-bit float pixel reads, virtual-vs-in-memory read agreement,
and its own **seam test** projecting a stack dataset and reading the exported result back through
`TiffStackLoader` + `ZMappingLoader`) and `ztracker.project.ZProjectorAccumulatorTest` (the
`Accumulator` ≡ `ZProjector.project` parity proof over randomized tie-heavy stacks **that now emit
NaN at ~1 pixel in 5** — p10.11 widened the generator because a NaN-free generator would have kept
this proof passing while covering none of the NaN guard, a test that looks like proof and is not —
plus a targeted both-paths NaN case, tie-breaking, global-vs-position indices, and the buffer-reuse
contract the scanner relies on). Those two pin the *numbers*; `ztracker.io.projector.ProjectionStackScannerMemoryTest`
(added p10.14) pins the *memory shape* the numbers are supposed to come from — it projects a
generated 1024×1024×100 stack forked under `-Xmx96m`, a heap the forbidden load-all design cannot
fit in, and is the sole reason `pom.xml` now declares `maven-surefire-plugin` with two executions
(**which also pins surefire at 3.2.5, the version the super-POM default already resolved to — a
consequence of having to declare the plugin, not a version bump**). See the "must stay streaming"
entry [Refactoring how the TIFF-stack input reads slices](docs/GOTCHAS.md#refactoring-how-the-tiff-stack-input-reads-slices) for the margins and the ways to break it.

`ztracker.io.projector.ProjectionStackScannerRealDataTest` complements — does **not** replace —
that synthetic suite by running the scanner against a **real Fiji-produced stack**:
`src/test/resources/ztracker/io/projector/reference_stack_crop.tif`, a byte-for-byte 53×68 / 3-slice
crop of an actual acquisition timepoint (uncompressed big-endian 8-bit, ImageJ 1.54p, `IJMetadata`
tag, labels `z = -2.000` / `z = 0.000` / `z = 2.000` — **all three carry the `z = ` prefix**; the
earlier shorthand here read as though slices 2 and 3 were bare numbers, which is precisely the
distinction [Tightening the slice-label number parsing](docs/GOTCHAS.md#tightening-the-slice-label-number-parsing) turns on). This is the repo's **only** binary fixture and its
authenticity is the whole point — **never open, convert, or re-save it**; the test asserts its exact
size (11,385 bytes) so an accidental re-encode fails loudly instead of quietly turning it into a
second synthetic fixture. It exists because the synthetic suite is mildly circular (it parses only
labels `FileSaver` itself wrote), so it can't prove the acquisition pipeline's real label text,
byte order, and metadata layout parse. It checks whole-frame projection sums and z-origin
histograms for both modes, named pixels traced from their slices through both projections, unsigned
8-bit reads on live data, and — the most valuable part — **tie-breaking on real pixels**: 8-bit
acquisitions saturate, so 250 of this crop's 3604 pixels (~7%) have two or more slices sharing the
max, making ties ordinary rather than the corner case the docs imply. Both an adjacent (layers 0,1)
and a non-adjacent (layers 0,2) tie are asserted to resolve to the lowest Z.

Tool 2's suite is `ztracker.core.extractor.ZSamplerTest` (sampling geometry: `SINGLE_PIXEL` reading
a value above the 16-bit range, `FOUR_NEIGHBOR`'s four corners, the `RADIUS` disk's membership rule,
edge footprints **clipped** rather than errored, and the empty-array return that a missing frame and
an out-of-bounds position share — plus the `PixelConvention` pairs: floor-vs-round anchoring, the
exact-integer boundary where the two diverge, and the negative coordinate that is in-bounds under
Center but out-of-bounds under Corner), `ztracker.core.extractor.ZExtractorTest` (index→Z end to
end, the NaN-Z causes kept distinct from each other — missing frame vs out-of-bounds vs every
sampled index unmapped — the `Math.round(NaN)` regression test that plants a marker at pixel
`(0, y)` to prove a NaN X is never sampled, convention threading, and `extractAll`'s combo
behaviour including `SINGLE_PIXEL` running once however many aggregations were asked for, with all
three `resolveComboOutputDir` layouts), `ztracker.io.extractor.TiffStackLoaderTest` (filename
parsing, then real headless TIFF round-trips: unsigned 16-bit reads across the full `0–65535` range,
32-bit `getf`+`Math.round` with values chosen so truncation would give a *different* answer, p10.12's
NaN → `NO_DATA` sentinel plus the infinities kept distinct from it,
mixed/8-bit/24-bit-RGB rejection, frame-gap mapping proved by each frame's own pixel, and the two
p10.1/p10.2 guards — per-frame dimensions in both accessor paths and both directions, and
digit-less filenames), and `ztracker.io.extractor.ZMappingLoaderTest` (the JSON regex: negatives,
decimals, scientific notation, integer-only values, multi-digit keys, surrounding non-entry content
ignored, and the empty/no-valid-entries throws). The stack-agnostic suites every tool leans on sit
alongside these: `FrameAlignerTest`, `ZAggregatorTest`, `TrackCsvLoaderTest`, `NpyExporterTest`,
`FijiPointsExporterTest`, and `TrackExportManagerTest`.

Tool 3's suite mirrors Tool 2's minus the mapping: `ztracker.io.topoj.TopoJStackLoaderTest` (float Z
surviving **un-rounded** through ImageJ's real headless TIFF I/O, 32-bit-only enforcement rejecting
16-/8-bit, the frame index read from whatever integer the base name **ends with** at any padding
width, rejection of names that don't end with one, and p10.1's dimension guard),
`ztracker.core.topoj.TopoJSamplerTest` (the same geometry `ZSamplerTest` covers, over a float stack,
plus fractional/negative Z preserved), and `ztracker.core.topoj.TopoJExtractorTest` (the sampled
float returned directly as Z, missing-frame vs out-of-bounds tallies kept separate, the same NaN-X
regression, `STATUS_NO_DATA` when *every* sampled pixel is NaN contrasted with partial-NaN samples
still aggregating over what's left, and `extractAll` parity). `ztracker.core.topoj.ExtractorEquivalenceTest`
is the cross-tool parity proof — see the Tool 3 description below for what it locks in.

`src/test/java/ztracker/core/Step4AlignmentDemo.java` is a **runnable walkthrough** (not a test —
it has a `main`, no `@Test`, so Surefire ignores it but it stays compiled against the real
`FrameAligner`). It prints `suggestOffset` / `suggestOffsetFromEnd` / `validate` results over
several CSV-vs-TIFF alignment scenarios so the step-4 suggest-and-correct behaviour can be
eyeballed. Run instructions are in the class javadoc.

`src/test/java/ztracker/core/extractor/Step5MethodsDemo.java` is the same kind of runnable walkthrough for
step 5. It samples a synthetic TIFF frame (with a planted outlier pixel) using every
`ZSampler.Method`, aggregates with every `ZAggregator.Method` to show MEDIAN's outlier robustness
vs MEAN, runs `ZExtractor.extractAll`'s sampling × aggregation cross product (collapsed from 6 to
5 combos since `SINGLE_PIXEL` only runs once regardless of aggregation method — see the Pluggable
methods section), places a
detection at the image edge to show RADIUS/FOUR_NEIGHBOR getting clipped while SINGLE_PIXEL stays
in-bounds, contrasts a genuinely out-of-bounds detection against a genuinely missing frame to show
`ExtractionResult.outOfBoundsCount` vs `missingFrameCount` being reported separately, and actually
exports two combinations via `TrackExportManager` into a temp dir to print the resulting
`<sampling>/<aggregation>/...` folder tree plus the full contents of the `export_report.txt`
file written alongside the .npy output, then builds a track with both an invalid-X/Y
detection and a separate invalid-Z detection to print the asymmetry side by side (X/Y drops
from both 2D and 3D; Z drops from 3D only), plants a marker pixel to prove `ZExtractor` never
samples a NaN X/Y (rather than `Math.round(Double.NaN) == 0` silently treating it as `x=0`), and
exports a track with `.npy` off but Results Table CSV on to show the report's trailing
`"Results Table: N/M pt"` segment, then plants a pixel value absent from the Z mapping to
contrast a detection that samples *only* that unmapped pixel (fails with
`STATUS_UNMAPPED_INDEX` — a third NaN-Z cause distinct from a missing frame or out-of-bounds
position) against one that samples it alongside mapped neighbors (`ZAggregator`'s NaN-filtering
lets the aggregate still succeed), and finally contrasts the `PIXEL_CORNER` (the p7.0 default)
vs `PIXEL_CENTER` pixel coordinate conventions on the same sub-pixel detection (for
`SINGLE_PIXEL` and `FOUR_NEIGHBOR`) to show which pixel(s) each convention actually samples.
Run instructions are in the class javadoc.

`src/test/java/ztracker/export/Step6ExportDemo.java` is the same kind of runnable walkthrough for
step 6's export machinery — it lives in `ztracker.export`, not `ztracker.core` like the other
Step-N demos, since it's exercising `NpyExporter`/`FijiPointsExporter`/`TrackExportManager`
directly rather than dialog-step logic. It complements rather than duplicates
`ztracker.core.extractor.Step5MethodsDemo`'s export section (which shows the per-track report and
multi-method folder layout). `main` prints the input track (Track_ID, Frame, X, Y — as if loaded
from a tracking CSV) **and** a separately-labeled synthetic Z table (explicitly noted as "NOT read
from any input" — a real run computes Z via `ZExtractor`, a tracking CSV has no Z column) first
thing, before any section runs; both are then threaded through every later section as parameters
instead of each section declaring its own copy, so there's exactly one set of numbers in the whole
demo and no risk of e.g. section 3 quietly using different frame numbers than what was printed at
the top. It prints the actual bytes of an `NpyExporter`-written `.npy` file
(magic, version, header dict, the 64-byte alignment padding, and a little-endian float64 value
decoded back), confirms that padding stays aligned to a single 64-byte block across shapes from
`(1, 1)` up to `(999999999, 999999999)` since the header dict's fixed text dominates its length,
shows `write2DTrack`/`write3DTrack`'s exact `[X,Y,T]` / `[X,Y,Z,T]` column order using the same
shared input/Z, and finally exports that track through every format at once (`.npy` ×2, Results
Table CSV, ROI `.zip`) to print a side-by-side table tracing each detection's X/Y through all four
outputs, ending in an explicit `RESULT: ... MATCH` / `MISMATCH DETECTED` line — making clear that
X/Y are the only coordinates that actually originate in the input CSV, and that **this demo only
prints the comparison — it
makes no assertions itself**; the real pass/fail check is `NpyExporterTest`,
`FijiPointsExporterTest`, and
`TrackExportManagerTest.export_preservesInputXYCoordinatesIdenticallyAcrossEveryFormat`, run via
`mvn test`. Run instructions are in the class javadoc.

## Critical Constraints

- **Must compile to Java 8 bytecode (class file version 52).** Fiji bundles a Java 8 JVM, so a higher target causes `UnsupportedClassVersionError` at plugin load time. `pom.xml` uses `maven-compiler-plugin` with `<release>8</release>` — **never raise this target.**
- **Only use Java 8 language features.** No switch expressions, no text blocks, no `var`, no `String.formatted()`. Streams and lambdas are fine.
- **No external runtime dependencies.** The only dependency is `net.imagej:ij` (scope `provided`, supplied by Fiji). This is deliberate: JSON is parsed by regex, NumPy `.npy` files are written in pure Java, and CSV is read with `BufferedReader`/`String.split`. Do not introduce libraries like Gson, Jackson, or Apache Commons.

## What the Plugin Does

The JAR registers **three tools** under `Plugins > ZTracker`:

Numbering follows **pipeline order** (produce → extract), not creation order.

**Tool 1 — Z-Projection + Origin Map (`ZProjectorPlugin`, added p8.0).** The upstream
*producer* of Tool 2's inputs. From a raw Z-stack it computes a min-Z or max-Z
intensity projection and, per pixel, tracks **which Z-layer won** (`argmax`/`argmin`) as an
integer index. Two **input types** are accepted (dialog dropdown, default = the first):
a folder of **Z-layer sub-folders** named by their physical Z value, each holding one TIFF per
timepoint (Z from the folder names); or a folder of per-timepoint **TIFF stacks** — one
multi-slice file per timepoint whose slices are the Z layers, with Z read from the ImageJ slice
labels (added p10.0, see the Tool 1 input types section below). Everything downstream of the
scan is shared, so both produce the identical output tree. It writes the indexed z-origin TIFFs (16-bit and/or 32-bit, user-selectable —
default 16-bit) plus the matching `index → Z` JSON mapping — i.e. exactly what Tool 2 loads. Native-Java port of
`max_z_projection_plus_z_tracking_v2.py` / `min_z_projection_plus_z_tracking_v2.py` (the two
scripts differ only in min vs max, captured by one `ZProjector.Mode`).

**Tool 2 — 3D Z-Coordinate Extractor (`ZTrackerPlugin`).** Extracts 3D Z-coordinates from
16-bit or 32-bit *indexed* TIFF projection stacks and exports cell tracks. Each TIFF pixel
value is an index into a JSON Z-mapping (`index → Z in µm`). Given 2D detections from a
tracking CSV (X, Y, frame, track ID), it samples pixel indices around each detection, maps
them to Z, aggregates, and exports 3D tracks. Native-Java port of
`3D_tracking_Jay_app_unified_v1.py`. **Tools 1 and 2 share the indexed-TIFF and JSON-mapping
formats**, so the round-trip (project → extract) is a first-class invariant, locked in by
`ProjectionExporterTest`'s seam test (writes the outputs, reads them back through the
extractor's own `TiffStackLoader` + `ZMappingLoader`). The extractor's (Tool 2) code path was
entirely unchanged when the projector (Tool 1) was added.

**Tool 3 — 3D Z-Extractor (TopoJ / direct-Z) (`TopoJTrackerPlugin`, added p9.0).** A second
flavour of the extractor for projection images whose pixel value **is** the Z coordinate in µm
directly — e.g. Fiji's **TopoJ** height maps — rather than an index into a JSON mapping. It is
structurally Tool 2 *minus* the index → Z lookup: it loads a folder of **32-bit float** TIFFs
(`TopoJStackLoader`, values kept un-rounded; 16-bit/8-bit are rejected — those belong to Tool 2),
samples them (`TopoJSampler`, same geometry as `ZSampler`), and aggregates the sampled values
**as-is** (`TopoJExtractor`, no mapping). Native-Java port of `3D_tracking_Jay_app_v2.py` (which
used a 32-bit-float projection stack and its own crude "TIFF must start at 0" frame check). It
**deliberately supersedes** that script's old behaviour with Tool 2's improvements: the
suggest-and-confirm `FrameAligner` offset (Step 4), per-point drop (no whole-track NaN/min-length/
max-Z-std filtering), median/mean only (no mode), and all four export formats. Per the
isolate-per-tool philosophy the genuinely-different pieces are **duplicated** (loader, sampler,
extractor, dialog) so Tool 2 stays byte-for-byte untouched; the stack-agnostic contracts are
**reused** directly (`ZAggregator`, `TrackData`, `ExtractionResult`, `TrackCsvLoader`,
`TrackExportManager`, `NpyExporter`, `FijiPointsExporter`, and the `ZSampler.Method` /
`ZSampler.PixelConvention` / `ZAggregator.Method` enums). `FrameAligner` — coupled to
`TiffStackLoader.LoadedStack` but reading only its frame map, never its pixels — is reused via
`TopoJStackLoader.LoadedFloatStack.frameView()`, a pixel-less `LoadedStack` frame-index adapter.
Since there is no mapping, the shared `ExtractionResult.numUnmapped` field is always 0 for Tool 3
(`TopoJExtractor` doesn't track it — it passes a zero array at construction) and
`STATUS_UNMAPPED_INDEX` never arises; its direct-Z analogue `ExtractionResult.STATUS_NO_DATA`
(added p9.0) marks the case where pixels were sampled but every one was NaN (a no-data pixel in
the float map). `TopoJExtractorTest` / `TopoJSamplerTest` cover the identity-Z + failure-classification
logic (I/O-free), and `TopoJStackLoaderTest` proves float Z values survive load un-rounded through
ImageJ's real headless TIFF read/write, that non-32-bit inputs are rejected, and that the frame
index is read from whatever integer the filename **ends with** — any prefix, any zero-padding width
(`frame7.tif` / `topoj_0007.tif` / `height_map_00000100.tif`), rejecting names that don't end with
an integer (see [Extracting the frame number from a filename](docs/GOTCHAS.md#extracting-the-frame-number-from-a-filename)).
`ExtractorEquivalenceTest` (in `ztracker.core.topoj`) is the **cross-tool parity proof**: it builds
an indexed stack + JSON map and the equivalent float stack (each pixel = its mapped Z, NaN if
unmapped), then asserts `ZExtractor` and `TopoJExtractor` produce identical `z`/`zStd`/`numSamples`
and missing/OOB/invalid tallies across every sampling × aggregation × convention combo — the one
allowed divergence (`numUnmapped`, and `STATUS_UNMAPPED_INDEX`↔`STATUS_NO_DATA`) asserted explicitly.
`ExtractorEquivalenceDemo` is the runnable, no-assertions walkthrough of the same comparison.

## Data Format Conventions

The pipeline's core output is `.npy` files:

- **3D export**: shape `(N, >=4)`, column order **`[X, Y, Z, T]`**.
- **2D export**: shape `(N, 3)`, column order **`[X, Y, T]`**.
- **X and Y are in PIXELS** (taken from the tracking CSV; converted to physical units downstream).
- **Z is in MICROMETERS**, pre-converted via the JSON mapping before saving (the indexed extractor, Tool 2) — or read straight from the float pixel value, which already is Z in µm (Tool 3 / TopoJ).
- **T is the frame number.**

The NPY writer (`NpyExporter`) is hand-rolled pure Java targeting the **NumPy v1.0 binary format**: 6-byte magic, header dict (`'<f8'`, `fortran_order: False`, shape), 64-byte alignment padding, little-endian `float64`, C-order. It **must stay byte-compatible with `numpy.load()`** — do not change layout, padding, or dtype. `NpyExporterTest` checks this directly (magic/version/dtype/fortran_order bytes, 64-byte header alignment across several row/column counts, exact little-endian float64 data, and `write2DTrack`/`write3DTrack`'s `[X,Y,T]`/`[X,Y,Z,T]` column order) using its own independent raw-byte reader, separate from `TrackExportManagerTest`'s — so a bug shared between the writer and one hand-rolled reader can't silently pass both suites. `TrackExportManagerTest.export_preservesInputXYCoordinatesIdenticallyAcrossEveryFormat` and `FijiPointsExporterTest`'s coordinate-preservation tests additionally confirm **X/Y** — the only coordinates actually present in the input CSV — survive unchanged through to `.npy` (both dimensions), Results Table CSV, and the ROI `.zip` (decoded back via `ij.io.RoiDecoder`), catching a column swap, wrong-index bug, or accidental unit conversion in any one format. Z is not an "input" in this sense — it's produced by `ZExtractor`, not read from the CSV — so these tests just confirm the already-computed Z value is written to the correct column/field, not that it round-trips from anywhere.

Other outputs: `FijiPointsExporter` writes one `PointROI` per detection (named `<trackID>_f<frame>`) into an XY ROI Manager `.zip`, plus a results-table CSV. It can also write XZ/YZ ROI `.zip` sets — `(X px, Z µm)`/`(Y px, Z µm)` per detection, Z unconverted, only for detections with a valid Z — via `ij.io.RoiEncoder` directly rather than through the on-screen `RoiManager`, which avoids a Swing list-rendering race the on-screen manager hit when all three ROI formats were written back-to-back (see the p6.1 entry in `docs/CHANGELOG.md`).

### Format parsing details

- **JSON Z-mapping** — parsed with regex `"(\d+)"\s*:\s*(-?[\d.]+(?:[eE][+-]?\d+)?)`; supports negatives, decimals, and scientific notation.
- **TIFF loading** — files are natural-sorted by the last integer in the filename (the trailing frame index), so 0- vs 1-based numbering and gaps are handled, and incidental numbers earlier in the name don't get mistaken for the frame index.
- **CSV columns** — auto-detected case-insensitively with aliases (X/POSITION_X, Y/POSITION_Y, FRAME/T/TIME/TIMEPOINT/Slice n°, TRACK_ID/ID/Track n°, RADIUS/SIZE); user can override. Rows with blank/NaN Frame or Track_ID, or blank/unparseable/NaN X or Y, are skipped (each counted and logged separately — see [Handling a row with unparseable X or Y](docs/GOTCHAS.md#handling-a-row-with-unparseable-x-or-y)). Default radius is 3.5 px, applied in **two** situations: when there is **no radius column at all**, and when a cell in one is blank, unparseable, `NaN`, infinite, or **not positive** (`<= 0`, so zero and negatives too). Only the second is counted and logged — substituting for a value the CSV actually stated is a quiet failure if unreported, whereas a missing column states nothing. See [Reading the radius column or sizing the sampling disk](docs/GOTCHAS.md#reading-the-radius-column-or-sizing-the-sampling-disk).
- **Export filtering** — `TrackExportManager` applies no whole-track quality filtering (no minimum length, no max-Z-std cutoff); every track is exported. 2D and 3D are gated per-point (see [Deciding what a bad detection removes from an export](docs/GOTCHAS.md#deciding-what-a-bad-detection-removes-from-an-export)), skipped for a track only if that dimension has zero valid points.

## Architecture

There are **three `PlugIn` entry points**, all thin orchestrators with no business logic.

`ZProjectorPlugin` (Tool 1, added p8.0) is the simpler one: show `ZProjectorDialog` → resolve
the dataset(s) (single = the picked folder; batch = each sub-folder that scans as a dataset for
the chosen input type, skipping the tool's own `min_z`/`max_z` output roots) → for each dataset,
open it as a `ProjectionSource` (which discovers z-layers/timepoints), then **streams one
timepoint at a time** (RAM-friendly, matching
the Python script): `ProjectionSource.projectTimepoint` returns the projection + z-origin
index map, `ProjectionExporter` writes the z-origin TIFF(s) at the selected bit depth(s) (16-bit
and/or 32-bit — default 16-bit; a single depth ~halves the per-timepoint write work), the matching
JSON mapping(s), and the 8-bit raw projection (always written). If 16-bit is chosen alone and an
index exceeds the uint16 range there's no 32-bit fallback and the timepoint gets no z-origin file
(logged). The dialog can request **both** projections (Max-Z *and*
Min-Z); the plugin loops projection × dataset. Output nesting depends on scope: **batch** groups
each dataset under a projection-type folder (`<out>/max_z/max_z_<dataset>/`), while **single**
drops that redundant grouping level and writes `<out>/max_z_<dataset>/` directly (only one
dataset). Either way the `max_z`/`min_z` prefix keeps both projections' folders from colliding.
It reuses no `ZTrackerPlugin`/`ZTrackerDialog` code (the shared
folder-picker layout is deliberately **duplicated**, not extracted, so the extractor UI is
untouched) but does reuse the `io`/`model` format contracts so its output loads straight back
into Tool 2.

#### Tool 1's two input types (`ProjectionSource`)

Both layouts implement `io.projector.ProjectionSource`, whose unit of work is **project one
timepoint** (not "load one timepoint") — that is what lets the stack layout stream without ever
holding a whole timepoint in memory, while leaving the folder layout's behaviour identical:

- **`FolderProjectionSource`** (input type A, the default) — a thin adapter over the original
  `ProjectionInputScanner`, which **p10.0 left byte-for-byte unchanged when it added input type
  B**. That is the point of the adapter: every timepoint still goes `loadTimepoint` →
  `ZProjector.project`, so type B's arrival could not alter type A's results or memory profile.
  Note the scope — the file is **not** unchanged in absolute terms, having since been modified
  by p10.5 (RGB rejection) and p10.6 (mixed-depth rejection), both inside `loadTimepoint`.
- **`ProjectionStackScanner.StackScan`** (input type B, added p10.0) — one multi-slice TIFF per
  timepoint, slices = Z layers. Z comes from the **ImageJ slice labels** (`z = -400.000`, or a
  label that is nothing but a number); layers are sorted **ascending by Z**, matching how type A
  sorts its Z-named sub-folders, so the JSON mapping's ordering *and* the ties→lowest-Z rule are
  identical for both types regardless of slice order in the file. A label with no readable Z is
  **rejected with a clear message** — `parseZLabel` deliberately refuses to mine a stray number
  out of arbitrary text, because a confidently wrong depth is far worse than a hard failure.
  The dataset's Z layers come from its **first** stack; a later timepoint may cover a subset of
  them (keeping global indices, exactly like a timepoint absent from some Z folders in type A),
  but one naming an unknown depth is skipped with a logged reason.

**Memory is the reason the stack path exists in this shape.** A single 401-slice 1051×1674
timepoint is ~700 MB on disk and would be ~2.8 GB held as `float[][]` per slice, so
`ProjectionStackScanner` reads slices **on demand** from a `FileInfoVirtualStack` and folds each
into `ZProjector.Accumulator` (the incremental form of `ZProjector.project` — same strict
comparison, so first-added wins ties; parity locked in by `ZProjectorAccumulatorTest`), reusing
one `float[][]` buffer across slices. Peak memory is then `w × h × (12 + bytes-per-pixel)` — the
reused float buffer plus the accumulator's float projection and int z-origin, **plus the source
slice's own `ImageProcessor`**, which is live alongside them (13 B/px for 8-bit input, 14 for
16-bit, 16 for 32-bit), and in no case a function of stack depth:
that real timepoint projects in ~2 s inside a 512 MB heap. Files that can't be opened virtually
(compressed, multi-IFD) fall back to a plain `IJ.openImage`. `readInto` goes straight to the
backing `byte[]`/`short[]`/`float[]` pixel array (masking to **unsigned** for the integer types)
rather than per-pixel `getf`, since a deep stack multiplies 1.7 M pixels by hundreds of slices.

`ZTrackerPlugin` (Tool 2) wires together the extraction pipeline and dialog, **interleaving the
multi-step dialog with I/O** so each user choice is validated against loaded data before the next step is shown:

1. **Dialog steps 1–2** — collect file paths + CSV format options.
2. **Load inputs** — `ZMappingLoader` (JSON regex), `TiffStackLoader` (TIFF folder → indexed stack), `TrackCsvLoader` (auto-detect CSV columns).
3. **Dialog step 3** — user confirms detected column mapping.
4. **Load CSV** into `TrackData`.
5. **Dialog steps 4–6** — frame offset, sampling/aggregation method, export config.
6. **Validate alignment** — `FrameAligner` checks CSV-vs-TIFF frame indexing.
7. **Extract** — `ZExtractor` produces an `ExtractionResult`.
8. **Export** — `TrackExportManager` groups by track and writes outputs (no whole-track filtering — every track is exported).

`TopoJTrackerPlugin` (Tool 3, added p9.0) is Tool 2's orchestrator with the JSON-mapping load
removed: Steps 1–2 collect the **32-bit float TIFF folder** + CSV (no JSON), `TopoJStackLoader`
loads the float stack, Steps 3–6 run exactly as Tool 2's, then `TopoJExtractor.extractAll`
produces the `ExtractionResult`s (sampled float = Z, no lookup) and the **same**
`TrackExportManager` / `ZExtractor.resolveComboOutputDir` handle export. It reuses no
`ZTrackerPlugin`/`ZTrackerDialog` code (the dialog is a deliberate **duplicate**, `TopoJTrackerDialog`,
so Tool 2's dialog is untouched) but shares every stack-agnostic contract.

### Package structure & philosophy

Keep the package layout clean — `model` / `io` / `core` / `project` / `export` / `ui`, each with a **single responsibility**. Within `io` / `core` / `export` / `ui` and the root `ztracker` package, **tool-exclusive** classes are further grouped into per-tool subpackages — `projector` (Tool 1), `extractor` (Tool 2), `topoj` (Tool 3) — while classes **shared** across tools stay at the responsibility-package root. (`project` and `model` have no subpackages: `project` is entirely Tool 1's, and `model` is entirely shared.) A couple of `topoj → extractor` references remain by design, since Tool 3 deliberately reuses some of Tool 2's types (`TiffStackLoader.LoadedStack`, `ZExtractor.MethodCombo`/`resolveComboOutputDir`). Prefer small, focused classes over large ones. Keep business logic out of the entry-point classes.

- `ztracker` — entry points, one per tool subpackage: `projector.ZProjectorPlugin` (Tool 1), `extractor.ZTrackerPlugin` (Tool 2), `topoj.TopoJTrackerPlugin` (Tool 3) (orchestration only).
- `ztracker.ui` — dialogs, one per tool subpackage: `extractor.ZTrackerDialog` — the 6-step dialog wizard. **All steps are non-modal** so the ImageJ Log window stays interactive/resizable while any step is open (the Step-4 per-track table lives in the Log). Steps 1, 4, 5, and 6 are custom AWT `Dialog`s created modeless (`new Dialog(..., false)`) and block the plugin thread with a `CountDownLatch` counted down on OK/Cancel/close — Step 1 is the resizable file picker, Step 4 the live-updating frame-alignment box, Step 5 the sampling/aggregation/pixel-convention method picker (uses a live `ItemListener` to disable the aggregation `Choice` when Sampling is `SINGLE_PIXEL` alone — see the Pluggable methods section — plus a third `Choice` for `ZSampler.PixelConvention`, Corner listed first as the default, no "All" option), Step 6 the output-directory-and-format picker (same `addInputGroup` grid layout as Step 1, with a `DirectoryChooser`-backed browse button instead of `GenericDialog.addDirectoryField`). Steps 2 and 3 use `NonBlockingGenericDialog` (ImageJ's non-modal `GenericDialog`, whose `showDialog()` still blocks the caller so the existing `wasCanceled()`/`getNext*()` usage is unchanged). Plugins run off the EDT, so blocking the plugin thread doesn't freeze the UI.
  `topoj.TopoJTrackerDialog` (Tool 3) is a deliberate **duplicate** of `ZTrackerDialog` (so Tool 2's
  dialog stays byte-for-byte unchanged), with one substantive difference: **Step 1 collects only
  the TIFF folder + CSV** — there is no JSON-mapping picker. Steps 2–6 are identical, and Step 4
  reuses `FrameAligner` against `LoadedFloatStack.frameView()`.
  `projector.ZProjectorDialog` (Tool 1) is a single modeless AWT `Dialog` in the same style — an
  input-type `Choice` (Z-layer sub-folders / TIFF stacks) asked **first**, then a scope
  `Choice` (single/batch), then a gray **structure line** that live-updates from both choices to
  spell out exactly what the input folder must contain (`describeStructure`; the dialog is packed
  while that label holds its widest text, since an AWT `Label` never grows after `pack()`), then
  input/output `DirectoryChooser` pickers via a
  **copy** of `addInputGroup` (duplicated here on purpose so `ZTrackerDialog` is byte-for-byte
  unchanged; the input folder needs no description of its own since the structure line covers it), then a
  projection `Choice` (Max-Z / Min-Z / **Both**) and a Z-origin bit-depth `Choice` (16-bit /
  32-bit / **Both**, default 16-bit). There is no raw-projection toggle — the 8-bit raw is always
  written. `Config.modes` is a `List<ZProjector.Mode>` (one entry, or both); `Config.write16Bit`/
  `write32Bit` carry the bit-depth selection (at least one always true); `Config.stackInput`
  carries the input type (`false` = z-layer sub-folders, the default).
- `ztracker.io` — input loaders. Shared at root: `TrackCsvLoader`. `extractor.ZMappingLoader` + `extractor.TiffStackLoader` (indexed loaders, Tool 2); `topoj.TopoJStackLoader` (Tool 3's 32-bit float stack); `projector.ProjectionSource` (Tool 1's layout-agnostic input contract) with its two implementations `projector.ProjectionInputScanner` + `projector.FolderProjectionSource` (z-layer/timepoint folder structure) and `projector.ProjectionStackScanner` (per-timepoint TIFF stacks).
- `ztracker.core` — extraction logic. Shared at root: `FrameAligner` + `ZAggregator`. `extractor.ZSampler` + `extractor.ZExtractor` (Tool 2); `topoj.TopoJSampler` + `topoj.TopoJExtractor` (Tool 3 — duplicates of the sampler/extractor geometry over a float stack, values taken as Z directly).
- `ztracker.project` — projection logic (`ZProjector`: I/O-free min/max projection + per-pixel z-origin index map, plus `ZProjector.Accumulator`, the incremental one-slice-at-a-time form used by the TIFF-stack input). Tool 1's counterpart to `core`; no subpackage since it's entirely Tool 1's.
- `ztracker.export` — output writers. Shared at root: `NpyExporter`, `FijiPointsExporter`, `TrackExportManager`. `projector.ProjectionExporter` (Tool 1's z-origin TIFFs + JSON mappings + raw projection).
- `ztracker.model` — data containers (`TrackData`, `ExtractionResult`) — shared, at root.

Menu registration for all three tools lives in `src/main/resources/plugins.config`.

### Data model: parallel arrays

Detection data is held as **parallel arrays indexed by detection position**, not as per-detection objects:

- `TrackData` — `double[] x, y`, `int[] frame`, `String[] trackId`, `double[] radius` (NaN if absent), plus the resolved column-name metadata and default radius.
- `ExtractionResult` — `double[] z` (µm; NaN when extraction fails), `double[] zStd`, `int[] numSamples`, `int[] numUnmapped`, plus per-detection/per-run metadata (`sampleStatus`, `samplingMethod`, `aggregationMethod`, `pixelConvention`, and the `missingFrameCount`/`outOfBoundsCount`/`invalidXYCount` tallies — see below).
- `TiffStackLoader`'s loaded stack — `int[][][] pixels` indexed `[stackIndex][y][x]` (16-bit or 32-bit indices), plus a `frame → stackIndex` map and sorted frame list (gaps are supported).

When editing extraction or export code, preserve the array-parallelism invariant: all arrays share the same length and index.

### Pluggable methods (enum-dispatched)

- `ZSampler.Method` — `RADIUS` (circular disk), `FOUR_NEIGHBOR` (bilinear corners), `SINGLE_PIXEL`.
- `ZAggregator.Method` — `MEDIAN`, `MEAN` (no `MODE` — removed as an option). Standard deviation uses the **population** divisor (n, not n−1).
- `ZSampler.PixelConvention` — `PIXEL_CORNER` (integer `i` = pixel `i`'s top-left corner,
  `[i, i+1)`, center at `i+0.5` — the **default**) or `PIXEL_CENTER` (integer `i` = pixel `i`'s
  center directly, `[i-0.5, i+0.5)` — the switchable alternate, and this plugin's original
  behavior). Unlike `Method`/`ZAggregator.Method`, this has no "All" option — always exactly
  one, threaded as a required parameter (no silent-default overload) through
  `ZSampler.sample`/`ZExtractor.extract`/`extractAll`/`ExtractionResult`. See the "pixel
  coordinate convention" entry, [Changing the pixel coordinate convention](docs/GOTCHAS.md#changing-the-pixel-coordinate-convention).

To add a sampling or aggregation strategy, extend the relevant enum and its dispatch.

Step 5 of the dialog lets either axis (sampling method, aggregation method) be set to **"All"**
instead of a single choice — `ZExtractor.extractAll` runs every requested combination (the full
sampling × aggregation cross product when both axes are "All") and returns a
`List<ZExtractor.MethodCombo>`. **`SINGLE_PIXEL` is an exception**: it samples exactly one pixel
per detection, so every aggregation method produces an identical result — `extractAll` runs it
only once regardless of how many aggregation methods were requested, instead of once per method.
The Step-5 UI mirrors this: it's now a custom modeless AWT dialog (like Steps 1, 4, 6) with an
`ItemListener` on the sampling `Choice` that disables the aggregation `Choice` (pinned to Median,
never actually read downstream) whenever Sampling is set to `SINGLE_PIXEL` alone — it stays
enabled when Sampling is "All", since Radius and 4-Neighbor still need it.
`ZExtractor.resolveComboOutputDir` decides each combo's export folder: `outputDir/<sampling>/<aggregation>/...`
(e.g. `outputDir/radius/median/`) for non-single-pixel combos when more than one combination was
run, but `outputDir/single_pixel/` (no aggregation subfolder) for `SINGLE_PIXEL` — collapsed for
the same reason its extraction is deduped. A single chosen method still exports flat into
`outputDir` as before. `ZTrackerPlugin` just calls this resolver; it doesn't compute paths itself.

## Gotchas — index

The full entries live in **[docs/GOTCHAS.md](docs/GOTCHAS.md)**, grouped by the same headings used
below. Each line here states only **what you would be doing** and **what goes wrong** — the *why*,
which is the part worth reading, is in the linked entry. Scan for a line that matches your task;
if one does, open it before you change anything.

> **This granularity is a first attempt.** The lines are deliberately thin so an unrelated session
> can skip the whole index at a glance. If a session ever misses a gotcha it should have caught,
> **the fix is a fatter index line, not abandoning the split** — the failure would mean that line
> did not carry enough to trigger a read, which is a wording problem, not a structural one.

**Build, deploy & repo hygiene**

- **[Counting tests after a patch](docs/GOTCHAS.md#counting-tests-and-updating-the-changelog-row-after-a-patch)** — summing `surefire-reports` silently overcounts, and a clean run prints **two** totals that must be added. The count itself is in [docs/TESTS.md](docs/TESTS.md).
- **[Editing a repo file from PowerShell](docs/GOTCHAS.md#editing-a-repo-file-from-powershell)** — `Set-Content` re-encodes UTF-8 to CP1252 and corrupts every em-dash in the file, including ones you never touched. Invisible in normal output.
- **[Running `mvn install` with Fiji open](docs/GOTCHAS.md#running-mvn-install-with-fiji-open)** — the superseded JAR cannot be deleted and survives, making Fiji register all three tools twice. `verify-deploy` fails the build on it.
- **[Indexing a PowerShell hashtable](docs/GOTCHAS.md#indexing-a-powershell-hashtable-whose-key-is-also-a-member-name)** — `$h.count` returns the entry count, not the `'count'` key. Surfaces as a type error far from the cause, and can silently delete generated text.
- **[Deciding whether executable content changed](docs/GOTCHAS.md#deciding-whether-executable-content-changed)** — a hash comparison always reports "changed", at both JAR and class level, so trusting it bumps `<patch.version>` on every patch. Compare `javap -c -p` output instead.
- **[Verifying a claim with a shell pipeline or a text comparison](docs/GOTCHAS.md#verifying-a-claim-with-a-shell-pipeline-or-a-text-comparison)** — a missing binary and a set-instead-of-multiset comparison both report **clean** without having compared anything. `0` from "did not run" is indistinguishable from `0` from "found nothing".

**Dialogs & frame alignment**

- **[Styling a freshly constructed AWT Label](docs/GOTCHAS.md#styling-a-freshly-constructed-awt-label)** — `getFont()` returns null before peer creation, so `getFont().deriveFont(...)` throws an NPE.
- **[Setting the CSV-to-TIFF frame offset](docs/GOTCHAS.md#setting-the-csv-to-tiff-frame-offset)** — tracking CSVs are often 0-indexed against 1-based TIFFs, and a silent misalignment corrupts every Z. Never remove the confirm step.

**Tool 1 — streaming & pixel reads**

- **[Refactoring how the TIFF-stack input reads slices](docs/GOTCHAS.md#refactoring-how-the-tiff-stack-input-reads-slices)** — load-all-then-project produces identical numbers but needs gigabytes; only a test under an artificial heap cap catches the regression.
- **[Reading pixels from a processor backing array](docs/GOTCHAS.md#reading-pixels-straight-from-a-processor-backing-array)** — `byte`/`short` are signed in Java, so intensity 200 arrives as −56 unless masked. `getf` already masks; the direct array read does not.
- **[Projecting float input that may contain NaN](docs/GOTCHAS.md#projecting-float-input-that-may-contain-nan)** — every comparison against NaN is false, so a NaN in the lowest layer could never be beaten and reported layer 0 as a real depth.

**Tool 1 — per-timepoint input validation**

- **[Accepting a new image type into the projector](docs/GOTCHAS.md#accepting-a-new-image-type-into-the-projector)** — `ColorProcessor.getf` hands back the packed ARGB int, so RGB input yields a structurally valid, entirely meaningless z-origin map.
- **[Handling a timepoint whose size or bit depth differs](docs/GOTCHAS.md#handling-a-timepoint-whose-size-or-bit-depth-differs)** — a size change is skipped, a depth change only noted. Reversing that makes the whole z-origin folder unloadable downstream.
- **[Adding a duplicate-Z check to either scanner](docs/GOTCHAS.md#adding-a-duplicate-z-check-to-either-scanner)** — the stack scanner rejects duplicates and the folder scanner accepts them; the asymmetry is deliberate, and a folder-side check would prevent no wrong output.
- **[Tightening the slice-label number parsing](docs/GOTCHAS.md#tightening-the-slice-label-number-parsing)** — `z = 1,5` truncates to 1.0 and `z:3/401` reads a slice number as a depth. Both are silently wrong and both are deliberately left.
- **[Testing the stack layout within-timepoint guards](docs/GOTCHAS.md#testing-the-stack-layout-within-timepoint-guards)** — the per-slice dimension and depth branches are unreachable; a test there could only assert ImageJ's own normalisation.
- **[Changing where the TIFF-stack input gets its Z](docs/GOTCHAS.md#changing-where-the-tiff-stack-input-gets-its-z)** — slice labels are the only source. Calibration looks like it works and is wrong, and an unreadable label must stay a hard failure.

**Tool 1 — run accounting & pre-flight**

- **[Counting written timepoints in `processDataset`](docs/GOTCHAS.md#counting-written-timepoints-in-processdataset)** — counting attempts rather than successes let a dataset where *every* timepoint failed report success, leaving an empty tree and an orphan mapping.
- **[Wording the end of a projection run](docs/GOTCHAS.md#wording-the-end-of-a-projection-run)** — a run that wrote nothing must not end on "complete". The dialog must carry the reasons itself, because `IJ.error` blocks the Log it would tell you to read.
- **[Adding a cross-timepoint check to the projector](docs/GOTCHAS.md#adding-a-cross-timepoint-check-to-the-projector)** — these sit above the `ProjectionSource` boundary and so are input-type-independent by construction; that property rests on one upstream `TreeSet`.
- **[Handling a timepoint filename with no digits](docs/GOTCHAS.md#handling-a-timepoint-filename-with-no-digits)** — no digits means no timepoint index. The 32-bit output then reads `32bit` as frame 32 and loads with no error at the wrong frame.
- **[Ordering the pre-flight name checks](docs/GOTCHAS.md#ordering-the-pre-flight-name-checks)** — digit-free first, duplicate-index second. Reversed, the duplicate check silently becomes bit-depth-dependent.
- **[Handling a sub-folder that is not a Z layer](docs/GOTCHAS.md#handling-a-sub-folder-that-is-not-a-z-layer)** — a misnamed layer is dropped in total silence, skewing the projection. It is a NOTE not an error, and one reset call keeps it once per run rather than per session.

**Tools 2/3 — loading stacks & mappings**

- **[Reading a 32-bit indexed TIFF pixel](docs/GOTCHAS.md#reading-a-32-bit-indexed-tiff-pixel)** — `Math.round(NaN)` is `0`, which is the lowest layer's own legitimate index, so a NaN pixel resolved to a real depth and reported `STATUS_OK`.
- **[Reading 16-bit versus 32-bit indexed pixels](docs/GOTCHAS.md#reading-16-bit-versus-32-bit-indexed-pixels)** — `getPixel` truncates toward zero on a float processor and can be off by one; 32-bit needs `getf` plus rounding.
- **[Relaxing the per-frame dimension check](docs/GOTCHAS.md#relaxing-the-per-frame-dimension-check)** — a short 16-bit frame zero-fills to index 0 and reports a real-looking Z; the 32-bit path shears rows instead. Both must stay hard errors.
- **[Extracting the frame number from a filename](docs/GOTCHAS.md#extracting-the-frame-number-from-a-filename)** — take the **last** digit run, or `32bit` becomes the frame number and every file collapses onto one frame. Missing and duplicate numbers are both refused up front.
- **[Parsing the Z-mapping JSON](docs/GOTCHAS.md#parsing-the-z-mapping-json)** — a repeated index used to last-win silently. The regex scans the whole file, so a stray `"n": v` in a comment counts as an entry and can now collide.

**Tools 2/3 — sampling & extraction**

- **[Reporting why a detection produced no Z](docs/GOTCHAS.md#reporting-why-a-detection-produced-no-z)** — an out-of-bounds position and a missing frame both surface as a NaN Z but need opposite fixes. Keep the counters and statuses separate.
- **[Setting the CSV-to-TIFF frame offset](docs/GOTCHAS.md#setting-the-csv-to-tiff-frame-offset)** — every detection is shifted by one constant; if it is wrong the whole run reads the wrong frames and reports missing-frame NaNs. *(Same entry as under Dialogs.)*
- **[Reading the radius column or sizing the sampling disk](docs/GOTCHAS.md#reading-the-radius-column-or-sizing-the-sampling-disk)** — `parseDouble` accepts `NaN` and `Infinity`; an infinite radius froze Fiji for ~1.8 × 10¹⁹ iterations. The disk is clamped at the frame diagonal.
- **[Changing the pixel coordinate convention](docs/GOTCHAS.md#changing-the-pixel-coordinate-convention)** — corner and center disagree about whether a near-zero coordinate is in bounds at all. That is inherent to the two conventions, not a bug to patch away.
- **[Sampling by rounded coordinates](docs/GOTCHAS.md#sampling-by-rounded-coordinates)** — `Math.round(Double.NaN)` is `0`, so a NaN X silently sampled pixel 0 and produced a confident `STATUS_OK` Z at a phantom pixel.

**CSV input**

- **[Supporting a new tracking CSV format](docs/GOTCHAS.md#supporting-a-new-tracking-csv-format)** — TrackMate has three metadata rows to skip and other trackers have none. Keep column detection alias-based rather than TrackMate-shaped.
- **[Handling a row with unparseable X or Y](docs/GOTCHAS.md#handling-a-row-with-unparseable-x-or-y)** — `parseDouble("NaN")` *succeeds*, so a garbage row used to vanish without even throwing. Each skip class is counted and logged separately.

**Export**

- **[Deciding what a bad detection removes from an export](docs/GOTCHAS.md#deciding-what-a-bad-detection-removes-from-an-export)** — one bad point never discards its whole track. Invalid X/Y drops it from 2D and 3D, a NaN Z from 3D only, and surviving frames are never renumbered.
- **[Reading the per-track export report](docs/GOTCHAS.md#reading-the-per-track-export-report)** — it is written on every run, but its 2D/3D verdict is npy-specific and reads "npy export off" for every track when npy is disabled, however healthy the points were.

## Deferred decisions — index

Costed and consciously left, not oversights. Full reasoning in **[docs/DECISIONS.md](docs/DECISIONS.md)**.

- **[Per-detection reporting of an oversized radius](docs/DECISIONS.md)** — the clamp turned a freeze into a plausible-looking result, and nothing reports that a detection's radius covered the entire frame.
- **[`zStd` cannot distinguish one valid sample from many](docs/DECISIONS.md)** — a single surviving sample reports `zStd = 0.0`, reading as maximal confidence. Currently invisible because nothing consumes `zStd`.
- **[`ZMappingLoader` key overflow](docs/DECISIONS.md)** — an absurdly long digit run in a mapping key throws an uncaught `NumberFormatException` instead of a clear message.
- **[`openStack`'s in-memory fallback](docs/DECISIONS.md)** — a compressed stack silently loads whole, forfeiting streaming, and nothing logs that it happened. Untested, with no known producer in this pipeline.
