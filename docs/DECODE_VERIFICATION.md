# Manual decode verification — Tool 3 (TopoJ)

**This is the only way to check Tool 3's decode against real data.** No automated test carries a
file from disk through `TopoJStackLoader` into `TopoJStackDecoder` — every decoder and conversion
test builds `float[][][]` in memory, and `TopoJStackLoaderTest` does real TIFF I/O but stops at the
loader. The suite can therefore stay green while the decoded numbers move. The probes that used to
cover this were deleted at p11.3 (see `DECISIONS.md`, *"The TopoJ decode has been verified against
real data only by throwaway probes"*), and the automated replacement is `BACKLOG.md`'s
*"An end-to-end decode test over a committed TopoJ fixture"*. Until that exists, run this.

**Run it before pushing any change to:** `TopoJZConversion`'s formula, tolerance, sentinel handling
or ambiguity rule; `TopoJStackDecoder`'s validation or conversion pass; `TopoJStackLoader`'s pixel
reading; or on accepting a TopoJ version whose initialisation literal or loop bound differs.

**The numbers below are two different things and must not be confused.** The **shape** of each
check is universal and is what you verify. The **worked example** is one acquisition's figures,
included so the shape is concrete — nobody else's data will reproduce them. Running this on your
own folder means checking the shape, not matching the digits.

---

## Before you start

1. Close Fiji. Run `mvn clean test` — it must be green. Then `mvn install`, which must end with
   `[verify-deploy] OK` and leave exactly one `z-tracker-v4-*.jar` in the plugins folder.
2. Launch Fiji, open **Window > Log**, and leave it open. Everything below appears there.
3. Check **Edit > Options > Memory & Threads**. The whole stack is held at once —
   `width × height × 4 bytes × frames` — so the heap must exceed it with room to spare.
4. Have ready: a **TopoJ height-map folder**, a **tracking CSV** covering the same frames, and the
   four numbers from the **source stack** TopoJ ran on (`Image > Properties…`).

> **Worked example.** 362 frames of 1051 × 1674 (636,893,388 pixels, ≈2.4 GB resident, 4 GB heap
> is comfortable); a TrackMate CSV of 5465 detections; source stack `zFirst = -400`, `zStep = 2`,
> `nSlices = 401`, and a TopoJ run made **without calibrating**, so `encodingScale = 1`.

Dialog inputs used throughout: Step 3 at the CSV defaults (header row `0`, skip `3`, radius `3.5`),
Step 5's frame offset as `FrameAligner` suggests it — `+1` in the worked example, where a 0-based
CSV meets 1-based TIFF filenames.

---

## Case 1 — the ambiguous scale

**What it needs:** a folder whose declared `encodingScale` makes `1.0` collide with a real depth,
i.e. `1 / encodingScale` is a whole number no greater than `nSlices - 1`. Any uncalibrated TopoJ
run gives `encodingScale = 1`, which always collides. **This is the case that must not silently
produce depths.**

**Expected shape.** The decode succeeds. The Log reports the parameters, then a classification with
**zero invalid** and **zero NaN**; the unassigned count is whatever share of the image TopoJ never
assigned. The slice-count line prints the implied minimum against the declared count — the implied
value can never exceed the declared one, so agreement is the good case and a shortfall means no
pixel was brightest at slice 1. Then the **ambiguity warning fires**, naming both depths `1.0`
could mean, how many pixels were discarded, and the recalibrate-and-re-run fix. Every sentinel
pixel becomes **NaN**, so the depth of the colliding slice **cannot appear** anywhere in the
output — expect the Z range to stop one step short at that end. Detections whose sampled pixels
were *all* sentinel report `noData`; detections that merely touched one still aggregate over the
rest.

> **Worked example.** Step 2: `-400 / 2 / 401 / 1`.
>
> ```
> [TopoJDecode] Parameters: zFirst=-400.000 µm | zStep=2.000 µm | nSlices=401 | encodingScale=1.000
> [TopoJDecode] 636,893,388 px decoded: 619,511,867 valid | 17,381,521 unassigned (1.0) |
>               0 below-threshold (-1.0) | 0 no-data (NaN) | 0 invalid
> [TopoJDecode] ⚠ WARNING — the value 1.0 is AMBIGUOUS under these parameters.
>                … slice 400 … -400.000 µm / 400.000 µm … 17,381,521 of 636,893,388 (2.73%) …
> ```
>
> Decoded Z spans **-398 to +398 µm, mean 126.517176**; `+400` — slice 400's depth — is absent,
> because `1.0` was refused. `noData` drops: **0** under Radius, **5** under 4-Neighbor, **12**
> under Single Pixel, out of 5465. (`619,511,867` is `636,893,388 - 17,381,521`.)

---

## Case 2 — a scale that cannot decode the data

**What it needs:** the *same* folder from Case 1, declared at a scale its values cannot sit on —
doubling a scale whose values are not all even is the simplest. **This case must be refused, not
decoded.**

**Expected shape.** The run **aborts before converting anything**. The error names the total count
of offending pixels, up to five of them with frame and coordinates, and restates what they were
checked against — quotient, tolerance, `nSlices`, and the legitimate value set. No output folder is
written, and the loaded pixels are left untouched. A wrong scale fails on a large fraction of the
image, not on a rare pixel, so this fires on the first frame rather than deep into a run.

> **Worked example.** Step 2: `-400 / 2 / 401 / **2**` on the Case 1 folder.
>
> ```
> 307911939 pixel(s) in this stack are neither a sentinel nor a legitimate encoded slice …
> First 5 of them: value 385.0 at frame 1 (x=0, y=0); …
> Why the first one failed: … 385.0 / encodingScale 2.0 = 192.5, which is not an integer in
> [1, 400] within the relative tolerance … (nSlices=401 …)
> ```
>
> That is **307,911,939 of 636,893,388 pixels — 48.35 %** — every odd value in the map.

---

## Case 3 — the unambiguous scale

**What it needs:** a folder produced by a TopoJ run over a **correctly calibrated** stack, so
`1 / encodingScale` is not a whole number and `1.0` can only be the sentinel. This is a *different
folder* from Case 1, not the same one re-declared — Case 2 is what happens when you try that.

**Expected shape.** The decode succeeds with **no ambiguity warning**. Sentinel pixels convert to
**`zFirst`** rather than NaN, because the value can only mean "brightest at slice 0". Compared with
Case 1 over the same acquisition, the sentinel count **falls** by exactly the number of pixels that
were genuinely the colliding slice, and the valid count rises by the same amount — the two must be
equal and opposite, since every pixel is one or the other. Detections that reported `noData` in
Case 1 now report a depth.

> **Worked example.** The recalibrated output of the same acquisition, Step 2 `-400 / 2 / 401 / 2`.
> Sentinel count **15,648,128** against Case 1's 17,381,521 — a fall of exactly **1,733,393**,
> matched by the same rise in the valid count.

---

## Pass condition

**Every checked property above must hold. Any deviation means the decode path changed** — treat it
as a regression until proved otherwise, and do not push.

On your own data, check the **shape**: no invalid pixels in Cases 1 and 3; the implied slice count
never exceeding the declared one; the sentinel becoming NaN when `1.0` is ambiguous and `zFirst`
when it is not; the ambiguity warning firing exactly when the collision exists and staying silent
when it does not; the wrong scale refused with nothing converted; and Case 1 vs Case 3 moving the
same count between sentinel and valid. **The worked figures are one acquisition's and yours will
differ.**

Two figures the p11.0 run produced are **not recorded anywhere** and cannot be restated here: the
per-frame spread of the unassigned sentinel, and the per-sampling-method Z ranges. The probes that
measured them were deleted at p11.3. If you need them, they have to be measured again.
