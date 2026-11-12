// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
"use strict";

const element = (id) => document.getElementById(id);
const pageSelect = element("page");
const image = element("image");
const status = element("status");
const overlay = element("overlay");
const review = element("review");
const note = element("note");
const uploadedImages = new Map();
let score;
let usingSample = true;

function fallbackReasons(event) {
  const reasons = [];
  if (event.clefInferred) reasons.push("inferred clef");
  if (event.durationFallback) reasons.push("duration fallback");
  return reasons;
}

function render() {
  const page = score.pages[Number(pageSelect.value)];
  const source = usingSample ? "../scale.png" : uploadedImages.get(page.sourcePage);
  element("preview").hidden = !source;
  if (source) image.src = source;
  overlay.replaceChildren();
  review.replaceChildren();
  note.textContent = "Select a note to see its pitch and timing.";

  const flagged = new Map();
  for (const event of page.events) {
    const reasons = fallbackReasons(event);
    if (!reasons.length) continue;
    const measure = flagged.get(event.measureIndex) ?? new Set();
    reasons.forEach((reason) => measure.add(reason));
    flagged.set(event.measureIndex, measure);
  }
  for (const [index, reasons] of [...flagged].sort(([a], [b]) => a - b)) {
    const line = document.createElement("li");
    line.textContent = `Measure ${index + 1} · ${[...reasons].join(", ")}`;
    review.append(line);
    const bounds = page.score.measures[index];
    if (!bounds) continue;
    const box = document.createElement("div");
    box.className = "measure";
    box.style.left = `${100 * bounds.left}%`;
    box.style.top = `${100 * bounds.top}%`;
    box.style.width = `${100 * (bounds.right - bounds.left)}%`;
    box.style.height = `${100 * (bounds.bottom - bounds.top)}%`;
    overlay.append(box);
  }
  for (const event of page.events) {
    if (!Number.isFinite(event.x) || !Number.isFinite(event.y)) continue;
    const reasons = fallbackReasons(event);
    const pitch = event.midi == null ? "Unpitched event" : `MIDI ${event.midi}`;
    const detail = [
      pitch, `measure ${event.measureIndex + 1}`,
      `onset ${event.startBeat} beats`, `duration ${event.durationBeats} beats`, ...reasons,
    ].join(" · ");
    const marker = document.createElement("button");
    marker.className = reasons.length ? "marker review" : "marker";
    marker.type = "button";
    marker.style.left = `${100 * event.x / page.width}%`;
    marker.style.top = `${100 * event.y / page.height}%`;
    marker.title = detail;
    marker.setAttribute("aria-label", detail);
    marker.addEventListener("click", () => { note.textContent = detail; });
    overlay.append(marker);
  }
  status.textContent = `Page ${page.sourcePage} · ${page.events.length} events · ${flagged.size} measures with fallbacks`
    + (source ? "" : " · Choose the matching source page image to see the overlay.");
}

function loadScore(value, sample) {
  if (value?.schemaVersion !== 1 || !Array.isArray(value.pages) || !value.pages.length ||
      value.pages.some((page) => !(page.width > 0 && page.height > 0) ||
        !Array.isArray(page.events) || !Array.isArray(page.score?.measures))) {
    throw new Error("Choose a recognition document matching score schema v1.");
  }
  uploadedImages.forEach((url) => URL.revokeObjectURL(url));
  uploadedImages.clear();
  score = value;
  usingSample = sample;
  element("sample-exports").hidden = !sample;
  pageSelect.replaceChildren(...value.pages.map((page, index) => new Option(`Page ${page.sourcePage}`, index)));
  pageSelect.disabled = false;
  render();
}

element("score").addEventListener("change", async ({ target }) => {
  const file = target.files[0];
  if (!file) return;
  try {
    loadScore(JSON.parse(await file.text()), false);
  } catch (error) {
    status.textContent = error.message;
  }
});
element("source").addEventListener("change", ({ target }) => {
  const file = target.files[0];
  if (!file || !score) return;
  const sourcePage = score.pages[Number(pageSelect.value)].sourcePage;
  const previous = uploadedImages.get(sourcePage);
  if (previous) URL.revokeObjectURL(previous);
  uploadedImages.set(sourcePage, URL.createObjectURL(file));
  usingSample = false;
  element("sample-exports").hidden = true;
  target.value = "";
  render();
});
pageSelect.addEventListener("change", render);

fetch("scale.json")
  .then((response) => {
    if (!response.ok) throw new Error("Could not load the bundled recognition example.");
    return response.json();
  })
  .then((value) => { if (!score) loadScore(value, true); })
  .catch((error) => {
    if (!score) status.textContent = `${error.message} Serve this directory over HTTP or choose a JSON file.`;
  });
