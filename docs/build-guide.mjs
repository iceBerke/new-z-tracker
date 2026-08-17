// Build the ZTracker user guide from a single Markdown source into three
// synchronized outputs: .html, .pdf, .txt.
//
//   Usage:  node docs/build-guide.mjs
//
// Edit ONLY docs/ZTracker_User_Guide.md, then re-run this script. It overwrites
// all three output files so they can never drift apart. No npm packages are
// required — it uses a small built-in Markdown parser plus Microsoft Edge
// (headless) for the PDF. If Edge isn't found, the .html and .txt are still
// written and only the PDF step is skipped.

import { readFileSync, writeFileSync, existsSync, statSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const DIR = dirname(fileURLToPath(import.meta.url));
const BASENAME = "ZTracker_User_Guide";
const mdPath = join(DIR, `${BASENAME}.md`);
const htmlPath = join(DIR, `${BASENAME}.html`);
const txtPath = join(DIR, `${BASENAME}.txt`);
const pdfPath = join(DIR, `${BASENAME}.pdf`);

// ---------------------------------------------------------------------------
// Parse Markdown into a flat list of block objects. Supports the subset used
// by this guide: h1-h3, paragraphs, unordered/ordered lists, pipe tables,
// horizontal rules, fenced code blocks, and ::: note / ::: tip callouts
// (whose bodies are parsed recursively, so a callout may hold lists/code).
// ---------------------------------------------------------------------------

// A line that starts (or ends) a ``` / ~~~ fence.
const FENCE_RE = /^\s*(`{3,}|~{3,})\s*\w*\s*$/;
// Lines that terminate a paragraph or a list item's lazy continuation.
const isBlockStart = l =>
  /^(#{1,3})\s/.test(l) || /^:::/.test(l) || /^---+\s*$/.test(l) ||
  /^\s*[-*]\s+/.test(l) || /^\s*\d+\.\s+/.test(l) || /^\s*\|/.test(l) ||
  FENCE_RE.test(l);

function parseBlocks(md) {
  return parseLines(md.replace(/\r\n/g, "\n").split("\n"));
}

function parseLines(lines) {
  const blocks = [];
  let i = 0;

  // Markdown allows a list item to wrap onto following indented lines ("lazy
  // continuation"). Pull those in so one item stays one item instead of being
  // split into a separate paragraph mid-sentence.
  const takeItem = (marker) => {
    let text = lines[i].replace(marker, "");
    i++;
    while (i < lines.length && lines[i].trim() !== "" &&
           /^\s+\S/.test(lines[i]) && !isBlockStart(lines[i])) {
      text += " " + lines[i].trim();
      i++;
    }
    return text;
  };

  while (i < lines.length) {
    const line = lines[i];

    if (line.trim() === "") { i++; continue; }

    // Fenced code block — kept verbatim, never inline-formatted.
    const fence = line.match(FENCE_RE);
    if (fence) {
      const delim = fence[1];
      const closeRe = new RegExp("^\\s*" + delim[0] + "{" + delim.length + ",}\\s*$");
      i++;
      const body = [];
      while (i < lines.length && !closeRe.test(lines[i])) { body.push(lines[i]); i++; }
      i++; // consume the closing fence (absent at EOF is fine)
      blocks.push({ type: "code", lines: body });
      continue;
    }

    // Callout fence:  :::note  ...  :::
    const callout = line.match(/^:::(\w+)\s*$/);
    if (callout) {
      const kind = callout[1];
      i++;
      const body = [];
      while (i < lines.length && !/^:::\s*$/.test(lines[i])) { body.push(lines[i]); i++; }
      i++; // skip closing :::
      blocks.push({ type: "callout", kind, blocks: parseLines(body) });
      continue;
    }

    // Heading
    const h = line.match(/^(#{1,3})\s+(.*)$/);
    if (h) { blocks.push({ type: "h", level: h[1].length, text: h[2].trim() }); i++; continue; }

    // Horizontal rule
    if (/^---+\s*$/.test(line)) { blocks.push({ type: "hr" }); i++; continue; }

    // Table (starts with a pipe, next line is the separator row)
    if (/^\s*\|/.test(line) && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|?\s*$/.test(lines[i + 1]) && lines[i + 1].includes("-")) {
      const rows = [];
      while (i < lines.length && /^\s*\|/.test(lines[i])) { rows.push(lines[i]); i++; }
      const cells = rows.map(r => r.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map(c => c.trim()));
      const header = cells[0];
      const body = cells.slice(2); // skip the |---|---| separator row
      blocks.push({ type: "table", header, body });
      continue;
    }

    // Unordered list
    if (/^\s*[-*]\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) items.push(takeItem(/^\s*[-*]\s+/));
      blocks.push({ type: "ul", items });
      continue;
    }

    // Ordered list
    if (/^\s*\d+\.\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) items.push(takeItem(/^\s*\d+\.\s+/));
      blocks.push({ type: "ol", items });
      continue;
    }

    // Paragraph (accumulate until a blank line or a new block starts)
    const para = [];
    while (i < lines.length && lines[i].trim() !== "" && !isBlockStart(lines[i])) {
      para.push(lines[i]); i++;
    }
    blocks.push({ type: "p", text: para.join(" ").trim() });
  }
  return blocks;
}

// ---------------------------------------------------------------------------
// HTML rendering
// ---------------------------------------------------------------------------
const esc = s => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// Inline: **bold**, _italic_ / *italic*, `code`.
//
// Code spans are lifted out to placeholders BEFORE the emphasis passes run, so
// markers inside them (`snake_case`, `**literal**`) survive untouched, then put
// back afterwards. The emphasis rules require a non-word character on each side
// of the delimiter, which is what keeps identifiers like export_report.txt from
// being read as italics — and, unlike the old `[\s.,)]` lookahead, correctly
// closes an italic run that is followed by a colon or any other punctuation.
const PH = "\u0000"; // never occurs in the source text

function protectCode(s, store) {
  return s.replace(/`([^`]+)`/g, (_, c) => `${PH}${store.push(c) - 1}${PH}`);
}
function emphasize(s) {
  s = s.replace(/\*\*([^*]+)\*\*/g, (_, c) => `<strong>${c}</strong>`);
  s = s.replace(/(^|[^\w*])\*([^*\s][^*]*)\*(?![\w*])/g, (_, pre, c) => `${pre}<em>${c}</em>`);
  s = s.replace(/(^|[^\w_])_([^_]+)_(?![\w_])/g, (_, pre, c) => `${pre}<em>${c}</em>`);
  return s;
}

function inlineHtml(s) {
  const codes = [];
  s = protectCode(esc(s), codes);
  s = emphasize(s);
  return s.replace(new RegExp(PH + "(\\d+)" + PH, "g"), (_, n) => `<code>${codes[n]}</code>`);
}

// Render a block list to an array of HTML fragments. Used for the document and,
// recursively, for the contents of a callout.
function renderBlocksHtml(blocks) {
  const parts = [];
  let stepCount = 0;
  for (const b of blocks) {
    switch (b.type) {
      case "h": {
        // An h3 of the form "Step N — ..." becomes a numbered step card.
        const stepMatch = b.level === 3 && b.text.match(/^Step\s+(\d+)\s*[—-]\s*(.*)$/);
        if (stepMatch) {
          stepCount++;
          parts.push(`<div class="step"><h3><span class="stepnum">${stepMatch[1]}</span>${inlineHtml(stepMatch[2])}</h3>`);
          parts.push("<!--STEP-OPEN-->");
        } else {
          parts.push(`<h${b.level}>${inlineHtml(b.text)}</h${b.level}>`);
        }
        break;
      }
      case "p": parts.push(`<p>${inlineHtml(b.text)}</p>`); break;
      case "ul": parts.push(`<ul>${b.items.map(it => `<li>${inlineHtml(it)}</li>`).join("")}</ul>`); break;
      case "ol": parts.push(`<ol>${b.items.map(it => `<li>${inlineHtml(it)}</li>`).join("")}</ol>`); break;
      case "hr": parts.push("<hr>"); break;
      case "code": parts.push(`<pre><code>${esc(b.lines.join("\n"))}</code></pre>`); break;
      case "callout":
        parts.push(`<div class="${b.kind === "tip" ? "tip" : "note"}">${renderBlocksHtml(b.blocks).join("\n")}</div>`);
        break;
      case "table": {
        const head = `<tr>${b.header.map(c => `<th>${inlineHtml(c)}</th>`).join("")}</tr>`;
        const body = b.body.map(r => `<tr>${r.map(c => `<td>${inlineHtml(c)}</td>`).join("")}</tr>`).join("");
        parts.push(`<table><thead>${head}</thead><tbody>${body}</tbody></table>`);
        break;
      }
    }
  }
  return parts;
}

function renderHtml(blocks) {
  // Close each step card at the next top-level boundary (h2/hr) or end.
  return pageShell(closeStepCards(renderBlocksHtml(blocks)));
}

// Steps are opened as a <div class="step"> and must be closed before the next
// h2, hr, or end of document. We rebuild the stream inserting </div> markers.
function closeStepCards(parts) {
  const out = [];
  let open = false;
  for (const p of parts) {
    if (p === "<!--STEP-OPEN-->") { open = true; continue; }
    const isBoundary = /^<h2>/.test(p) || p === "<hr>" || /^<div class="step">/.test(p);
    if (open && isBoundary) { out.push("</div>"); open = false; }
    out.push(p);
    if (/^<div class="step">/.test(p)) open = true;
  }
  if (open) out.push("</div>");
  return out.join("\n");
}

function pageShell(inner) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>ZTracker — Quick User Guide</title>
<style>
  @page { size: A4; margin: 18mm 16mm; }
  * { box-sizing: border-box; }
  body { font-family: "Segoe UI", Arial, sans-serif; color: #1c1c1c; line-height: 1.5; font-size: 11pt; margin: 0; }
  h1 { font-size: 22pt; margin: 0 0 4px 0; color: #14406b; }
  h1 + p em { color: #555; font-style: normal; }
  h2 { font-size: 14pt; color: #14406b; border-bottom: 2px solid #d6e2f0; padding-bottom: 4px; margin: 26px 0 10px 0; }
  h3 { font-size: 12pt; margin: 16px 0 4px 0; color: #222; }
  p { margin: 6px 0; }
  ul, ol { margin: 6px 0; padding-left: 22px; }
  li { margin: 4px 0; }
  code { background: #f2f4f7; border: 1px solid #e2e6eb; border-radius: 3px; padding: 1px 5px; font-family: "Consolas", monospace; font-size: 9.5pt; }
  pre { background: #f7f9fc; border: 1px solid #dde6f1; border-radius: 4px; padding: 9px 12px; margin: 10px 0; overflow-x: auto; page-break-inside: avoid; }
  pre code { background: none; border: none; padding: 0; font-size: 9.5pt; line-height: 1.4; white-space: pre; }
  .step { background: #f7f9fc; border: 1px solid #dde6f1; border-left: 4px solid #2f6db5; border-radius: 4px; padding: 10px 14px; margin: 12px 0; page-break-inside: avoid; }
  .step h3 { margin-top: 0; color: #14406b; }
  .stepnum { display: inline-block; background: #2f6db5; color: #fff; border-radius: 50%; width: 22px; height: 22px; text-align: center; line-height: 22px; font-weight: bold; font-size: 10pt; margin-right: 6px; }
  table { border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 10pt; }
  th, td { border: 1px solid #d6dbe1; padding: 6px 9px; text-align: left; vertical-align: top; }
  th { background: #eaf0f7; color: #14406b; }
  .note { background: #fff8e6; border: 1px solid #f0e0a8; border-left: 4px solid #e0b000; border-radius: 4px; padding: 8px 12px; margin: 12px 0; font-size: 10pt; }
  .tip { background: #edf7ee; border: 1px solid #c3e2c6; border-left: 4px solid #46a04e; border-radius: 4px; padding: 8px 12px; margin: 12px 0; font-size: 10pt; }
  hr { border: none; border-top: 1px solid #ddd; margin: 26px 0 8px 0; }
  strong { color: #14406b; }
</style>
</head>
<body>
${inner}
</body>
</html>
`;
}

// ---------------------------------------------------------------------------
// Plain-text rendering
// ---------------------------------------------------------------------------
// Same code-span protection and emphasis rules as the HTML path, so the two
// outputs can never disagree about what is a marker and what is literal text.
const stripInline = s => {
  const codes = [];
  s = protectCode(s, codes);
  s = s.replace(/\*\*([^*]+)\*\*/g, "$1")
       .replace(/(^|[^\w*])\*([^*\s][^*]*)\*(?![\w*])/g, "$1$2")
       .replace(/(^|[^\w_])_([^_]+)_(?![\w_])/g, "$1$2");
  return s.replace(new RegExp(PH + "(\\d+)" + PH, "g"), (_, n) => codes[n]);
};

function wrap(text, width, indent = "") {
  const words = text.split(/\s+/);
  const lines = [];
  let cur = "";
  for (const w of words) {
    if ((cur + " " + w).trim().length > width) { lines.push(indent + cur.trim()); cur = w; }
    else cur += " " + w;
  }
  if (cur.trim()) lines.push(indent + cur.trim());
  return lines.join("\n");
}

// Render a block list to an array of text lines. Used for the document and,
// recursively, for the contents of a callout.
function renderTxtBlocks(blocks, W) {
  const out = [];
  for (const b of blocks) {
    switch (b.type) {
      case "h": {
        const t = stripInline(b.text);
        if (b.level === 1) { out.push("=".repeat(W), t.toUpperCase(), "=".repeat(W), ""); }
        else if (b.level === 2) { out.push("", t.toUpperCase(), "-".repeat(W)); }
        else { out.push("", t); }
        break;
      }
      case "p": out.push(wrap(stripInline(b.text), W)); break;
      case "ul": for (const it of b.items) out.push(wrap(stripInline(it), W - 2, "  ").replace(/^ {2}/, "  - ")); break;
      case "ol": b.items.forEach((it, n) => out.push(wrap(stripInline(it), W - 4, "    ").replace(/^ {4}/, `  ${n + 1}. `))); break;
      case "hr": out.push("", "-".repeat(W)); break;
      // Code stays verbatim — indented, never wrapped, never inline-stripped.
      case "code": for (const l of b.lines) out.push("    " + l); break;
      case "callout": {
        // Label on its own line, body indented, so any lists inside keep their
        // shape instead of being flattened into one run of prose.
        out.push(b.kind === "tip" ? "TIP:" : "NOTE:");
        // Entries may themselves be multi-line (wrap() joins with \n), so split
        // before indenting or only the first physical line would be indented.
        for (const seg of renderTxtBlocks(b.blocks, W - 2)) {
          for (const l of String(seg).split("\n")) out.push(l === "" ? "" : "  " + l);
        }
        break;
      }
      case "table": {
        const rows = [b.header, ...b.body].map(r => r.map(stripInline));
        const cols = rows[0].length;
        const widths = Array.from({ length: cols }, (_, c) => Math.max(...rows.map(r => (r[c] || "").length)));
        const fmt = r => r.map((c, ci) => (c || "").padEnd(widths[ci])).join("  ").trimEnd();
        out.push(fmt(rows[0]));
        out.push(widths.map(w => "-".repeat(w)).join("  "));
        for (const r of rows.slice(1)) out.push(fmt(r));
        break;
      }
    }
    if (b.type !== "h" && b.type !== "ul" && b.type !== "ol") out.push("");
  }
  return out;
}

function renderTxt(blocks) {
  return renderTxtBlocks(blocks, 72).join("\n").replace(/\n{3,}/g, "\n\n").trimStart() + "\n";
}

// ---------------------------------------------------------------------------
// PDF via Microsoft Edge (headless). Returns true on success.
// ---------------------------------------------------------------------------
function findEdge() {
  const candidates = [
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
  ];
  return candidates.find(p => existsSync(p));
}

function buildPdf() {
  const edge = findEdge();
  if (!edge) { console.log("  · PDF skipped (Microsoft Edge not found)"); return false; }

  // Record the existing PDF's mtime so we can tell a genuine (re)write from a
  // silent no-op that leaves a STALE file in place. `existsSync` alone is not
  // enough: if the PDF is open in a viewer, Edge can't overwrite it and exits
  // without error, and the old file still "exists" — which would otherwise be
  // reported as success.
  const mtimeBefore = existsSync(pdfPath) ? statSync(pdfPath).mtimeMs : 0;

  const fileUrl = "file:///" + htmlPath.replace(/\\/g, "/");
  const res = spawnSync(edge, [
    "--headless", "--disable-gpu", "--no-pdf-header-footer",
    `--print-to-pdf=${pdfPath}`, fileUrl,
  ], { stdio: "ignore" });

  if (res.error || !existsSync(pdfPath)) { console.log("  · PDF step failed"); return false; }
  if (statSync(pdfPath).mtimeMs <= mtimeBefore) {
    console.log("  · PDF NOT updated — the existing " + BASENAME + ".pdf is locked "
      + "(is it open in a viewer?). Close it and re-run. The .html/.txt are current.");
    return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
const md = readFileSync(mdPath, "utf8");
const blocks = parseBlocks(md);

writeFileSync(htmlPath, renderHtml(blocks), "utf8");
console.log(`  · wrote ${BASENAME}.html`);

writeFileSync(txtPath, renderTxt(blocks), "utf8");
console.log(`  · wrote ${BASENAME}.txt`);

if (buildPdf()) console.log(`  · wrote ${BASENAME}.pdf`);

console.log("Done. Edit ZTracker_User_Guide.md and re-run to regenerate all three.");
