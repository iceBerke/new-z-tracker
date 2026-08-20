# Test suite index

What exists and roughly what it covers — an index, not a restatement of the assertions. For
*why* a given guard exists, see the entry it belongs to in [docs/GOTCHAS.md](GOTCHAS.md); the
tests are named after the behaviour they pin, so the name plus the entry is usually enough.

Run everything with:

```bash
mvn clean test
```

---

## Test counts: two Surefire executions, 278 + 1 = 279

`mvn clean test` prints **two** `Tests run:` totals and the suite size is their **sum**:

| Execution | Tests | Heap |
|-----------|------:|------|
| `default-test` | 278 | default |
| `streaming-invariant-test` | 1 | `-Xmx96m` |
| **Total** | **279** | |

Do not read the first total as the whole suite. **The split is the point, not an accident of
configuration.** `ProjectionStackScannerMemoryTest` asserts that Tool 1's TIFF-stack input still
reads slices one at a time rather than loading a whole timepoint, and the only thing that can
prove a memory property is a heap too small for the forbidden design. Surefire cannot vary
`-Xmx` per test class within one execution, hence the second one. **Collapsing the two
executions back into one silently deletes that guard** — the test would still pass, because a
load-all rewrite produces byte-identical numbers; it would simply stop proving anything.

Also: do not take counts by summing `target/surefire-reports/*.xml`. That directory keeps
reports from any class run since the last `clean`, including throwaway probe classes, so the sum
silently overcounts. Take the number from a clean run.

*This file is where the count itself lives. The incident that produced the rule — a stale
report inflating 226 to 227, and the wrong number reaching a changelog row that had only been
computed, never edited — is recorded in
[Counting tests after a patch](GOTCHAS.md#counting-tests-and-updating-the-changelog-row-after-a-patch).*

---

## Test classes

**Tool 1 — Z-Projection + Origin Map**

| Class | @Test | Covers |
|-------|------:|--------|
| `ztracker.project.ZProjectorTest` | 10 | Pure-logic projection, no ImageJ and no filesystem: min/max selection, ties to the first layer, missing-layer index remapping, and the NaN rule |
| `ztracker.project.ZProjectorAccumulatorTest` | 8 | `ZProjector.Accumulator`, the incremental form the stack input streams into — above all its parity with `project` |
| `ztracker.io.projector.ProjectionInputScannerTest` | 24 | Input type A (Z-layer sub-folders): numeric z-sort with negatives and gaps, timepoint filename union/dedup, and global z-indices when a timepoint is absent from some layers |
| `ztracker.io.projector.ProjectionStackScannerTest` | 21 | Input type B (one TIFF stack per timepoint): Z parsed from ImageJ slice labels, ascending-Z ordering, unsigned pixel reads, virtual-vs-in-memory agreement |
| `ztracker.io.projector.ProjectionStackScannerRealDataTest` | 6 | The same scanner against a **real Fiji-produced stack**, complementing (not replacing) the synthetic suite, which only ever parses labels it wrote itself |
| `ztracker.io.projector.ProjectionStackScannerMemoryTest` | 1 | The streaming invariant, under its own `-Xmx96m` execution — see the section above |
| `ztracker.export.projector.ProjectionExporterTest` | 9 | The exporter, plus the **produce→consume seam**: output is read back through Tool 2's own loaders |
| `ztracker.projector.ZProjectorPluginTest` | 27 | The plugin's pure, I/O-free decision helpers — output nesting, dimension-mismatch reporting, and the two pre-flight name checks |

**Tool 2 — indexed 3D Z-Coordinate Extractor**

| Class | @Test | Covers |
|-------|------:|--------|
| `ztracker.core.extractor.ZSamplerTest` | 14 | Sampling geometry: the radius disk's membership rule, four-neighbour corners, single-pixel reads above the 16-bit range, edge footprints clipped rather than errored, the two pixel conventions, and the radius clamp |
| `ztracker.core.extractor.ZExtractorTest` | 14 | Index→Z end to end, the NaN-Z causes kept distinct (missing frame vs out-of-bounds vs every index unmapped), the `Math.round(NaN)` regression, and `extractAll`'s combo behaviour |
| `ztracker.io.extractor.TiffStackLoaderTest` | 20 | The indexed 16-/32-bit loader: filename parsing, unsigned reads, the NaN sentinel, mixed/8-bit/RGB rejection, per-frame dimensions, and the missing/duplicate frame-number guards |
| `ztracker.io.extractor.ZMappingLoaderTest` | 11 | The JSON mapping regex: negatives, decimals, scientific notation, multi-digit keys, surrounding non-entry content ignored, and duplicate keys refused |

**Tool 3 — TopoJ / direct-Z extractor**

| Class | @Test | Covers |
|-------|------:|--------|
| `ztracker.core.topoj.TopoJSamplerTest` | 9 | Geometry parity with `ZSampler` over a float stack — same pixels, the sampled value simply *is* the Z |
| `ztracker.core.topoj.TopoJExtractorTest` | 9 | Identity-Z extraction and failure classification, including `STATUS_NO_DATA` for an all-NaN sample |
| `ztracker.io.topoj.TopoJStackLoaderTest` | 10 | The 32-bit float loader: stored values surviving un-rounded — TopoJ's encoded counters, not yet depths — 32-bit-only enforcement, the trailing-integer frame rule, and the dimension guard |
| `ztracker.core.topoj.ExtractorEquivalenceTest` | 2 | The **cross-tool parity proof**: Tools 2 and 3 produce identical results across every sampling × aggregation × convention combination, with the one allowed divergence asserted explicitly |
| `ztracker.topoj.TopoJTrackerPluginTest` | 2 | The ambiguity warning text — the plugin's only non-orchestration logic. It is tested because the symptom it announces is an **absence**: without it a user sees a Z range that is merely short at one end. Asserts both candidate depths, what was discarded, the recalibration fix, and that no warning is emitted when the sentinel cannot collide |
| `ztracker.core.topoj.TopoJStackDecoderTest` | 9 | The whole-stack decode Tool 3 runs after loading: validation strictly before mutation (a failed stack is left byte-for-byte untouched), NaN passing through as no-data rather than refusing the file, one off-lattice pixel failing everything, and the per-frame and implied-slice-count reporting |
| `ztracker.core.topoj.TopoJZConversionTest` | 13 | The single-value TopoJ→Z decode the stack decoder applies: sentinel classification, the relative lattice tolerance a non-terminating `encodingScale` needs, and above all the same `1.0f` resolving to a depth or to NaN purely by whether it collides with a legitimate slice |

**Shared across tools**

| Class | @Test | Covers |
|-------|------:|--------|
| `ztracker.core.FrameAlignerTest` | 17 | Step-4 offset suggestion and per-track alignment validation |
| `ztracker.core.ZAggregatorTest` | 8 | Median and mean, NaN excluded before computing, and standard deviation on the **population** divisor |
| `ztracker.io.TrackCsvLoaderTest` | 6 | TrackMate and non-TrackMate CSV shapes, alias column detection, and the rows that are skipped or defaulted (bad X/Y, bad frame or track id, bad radius) |
| `ztracker.export.NpyExporterTest` | 5 | The hand-rolled NumPy v1.0 writer, checked with its **own** raw-byte reader independent of `TrackExportManagerTest`'s |
| `ztracker.export.FijiPointsExporterTest` | 5 | One ROI per detection, the XZ/YZ projection sets, the results-table header, and X/Y preservation |
| `ztracker.export.TrackExportManagerTest` | 19 | Per-point dropping (never whole-track), 2D/3D gated independently, drop reasons that do not conflate, frame numbers never renumbered, and the per-track report |

**Total: 279 `@Test` methods across 25 classes.**

---

## Demos — not tests

Four classes have a `main()` and **no `@Test`**, so Surefire ignores them entirely. They are
runnable walkthroughs: they **assert nothing** and can never fail a build. They exist to be read,
and they stay compiled against the real production classes on every `mvn test`, so they cannot
silently rot into referring to code that no longer exists.

| Demo | Shows |
|------|-------|
| `ztracker.core.Step4AlignmentDemo` | `suggestOffset` / `suggestOffsetFromEnd` / `validate` across several CSV-vs-TIFF alignment scenarios |
| `ztracker.core.extractor.Step5MethodsDemo` | Every sampling and aggregation method on one synthetic frame — median's outlier robustness, edge clipping, out-of-bounds vs missing frame, the export folder tree, the NaN-X guard, and both pixel conventions |
| `ztracker.export.Step6ExportDemo` | The actual bytes of a `.npy` file, header padding across shapes, `[X,Y,T]` / `[X,Y,Z,T]` column order, and one detection's X/Y traced through all four export formats |
| `ztracker.core.topoj.ExtractorEquivalenceDemo` | The Tool 2 vs Tool 3 comparison side by side — the no-assertions counterpart to `ExtractorEquivalenceTest` |

Each has a committed `<DemoName>_output.txt` beside its source. **Regenerate it whenever
behaviour changes**, so the snapshot keeps matching what the code actually prints. To run one
(from the project root):

```bash
mvn test-compile
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
     -cp "target/classes;target/test-classes;$(cat cp.txt)" \
     ztracker.core.Step4AlignmentDemo > src/test/java/ztracker/core/Step4AlignmentDemo_output.txt 2>&1
```

Substitute the class and output path for the other three. The UTF-8 flags are not optional:
Java's default stdout encoding on Windows is not UTF-8, and the output contains `—`, `→`, `µ`
and `✓`.

**The output is deterministic (since p10.19) and must stay that way.** Two demos used to print
the randomly-named system temp directory they exported into, which meant the snapshot changed on
every run and a reader could not tell a real behaviour change from a new temp path. Those lines
now print a masked placeholder naming *what* the directory is rather than *where* it is. If you
add output to a demo, keep it free of absolute paths, timestamps, hash codes and unordered map
iteration — anything that varies between runs makes the snapshot worthless as evidence.

---

## Fixtures

**Almost everything is generated at test time.** Tests that need a TIFF, a CSV or a JSON mapping
write it into a JUnit `@TempDir` and read it back through the real loaders, so the fixture and
the code that reads it can never drift apart, and nothing binary accumulates in the repo.

`src/test/resources` therefore holds exactly **one** file:

```
src/test/resources/ztracker/io/projector/reference_stack_crop.tif   11,385 bytes
```

A byte-for-byte 53×68 / 3-slice crop of a real acquisition timepoint (uncompressed big-endian
8-bit, ImageJ 1.54p, slice labels `z = -2.000` / `z = 0.000` / `z = 2.000`). It exists because
the synthetic suite is mildly circular — it only ever parses labels it wrote itself — so it
cannot prove the real acquisition pipeline's label text, byte order and metadata layout parse.

> **Never open, convert, or re-save it.** Its authenticity is the entire point.
> `ProjectionStackScannerRealDataTest` asserts the exact size above, so an accidental re-encode
> fails loudly instead of quietly turning it into a second synthetic fixture. `.gitattributes`
> marks `*.tif` as binary so line-ending normalisation cannot touch it either.

---

## Proposed

**Today the demos are run and their snapshots regenerated by hand.** A proposal to run them in the
build and **fail on any diff** — rather than auto-regenerate, which would make the snapshots
permanently non-stale and permanently uninformative — is recorded in
[docs/BACKLOG.md](BACKLOG.md), with its reasoning.
