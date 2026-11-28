export function noteDraftKey(userId) {
  const id = String(userId || "");
  if (!/^[1-9]\d{0,18}$/.test(id)) throw new Error("Invalid draft owner");
  return "citymate:note-draft:v1:" + id;
}

export function cleanNoteDraft(value) {
  if (!value || typeof value.title !== "string" || value.title.length > 255
      || typeof value.content !== "string" || value.content.length > 10000
      || typeof value.shopId !== "string" || !/^(?:[1-9]\d{0,18})?$/.test(value.shopId)
      || !Array.isArray(value.pictures) || value.pictures.length > 9
      || value.pictures.some(p => typeof p !== "string" || p.length > 256
        || !/^\/blogs\/[\w./-]+\.(?:jpe?g|png|webp)$/i.test(p) || p.includes(".."))) return null;
  return { title: value.title, content: value.content, shopId: value.shopId,
    pictures: [...new Set(value.pictures)] };
}

export function readNoteDraft(raw) {
  if (!raw || raw.length > 25000) return null;
  try {
    const value = JSON.parse(raw);
    const draft = cleanNoteDraft(value.draft);
    if (value.version !== 1 || !draft) return null;
    return { draft, uncertain: value.uncertain === true,
      updatedAt: Number.isFinite(Date.parse(value.updatedAt)) ? value.updatedAt : "" };
  } catch { return null; }
}

export function encodeNoteDraft(draft, uncertain = false) {
  const clean = cleanNoteDraft(draft);
  if (!clean) throw new Error("Invalid note draft");
  return JSON.stringify({version: 1, draft: clean, uncertain: uncertain === true,
    updatedAt: new Date().toISOString()});
}

export function hasNoteContent(draft) {
  return !!(draft.title.trim() || draft.content.trim() || draft.pictures.length);
}
