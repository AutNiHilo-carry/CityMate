import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { claimPreview, claimFingerprint } from "../scripts/assistant-helpers.js";
import { safeReturn, contextualHref, returnTarget } from "../scripts/api.js";
import { noteDraftKey, readNoteDraft, encodeNoteDraft, hasNoteContent } from "../scripts/note-draft.js";

export function pageVm(api, { storage = memoryStorage(), pathname = "/vouchers.html", search = "", clipboard, browserUrl } = {}) {
  let options;
  const navigation = [];
  const copiedOrderIds = [];
  const replacedUrls = [];
  const source = readFileSync(new URL("../scripts/app.js", import.meta.url), "utf8")
    .replace(/^import .*;\r?\n/gm, "");
  runInNewContext(source, {
    URL, URLSearchParams, claimPreview, claimFingerprint, noteDraftKey, readNoteDraft, encodeNoteDraft, hasNoteContent,
    ...(browserUrl ? { history: { replaceState: (_, _title, value) => replacedUrls.push(value) } } : {}),
    localStorage: storage,
    navigator: { clipboard: clipboard || {writeText: async text => copiedOrderIds.push(text)} },
    location: { search, pathname, origin: "http://localhost", href: browserUrl,
      assign(value) { navigation.push(value); } },
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    createApi: () => api,
    images() {}, imagePath() {}, money() {}, safeReturn, contextualHref, returnTarget,
    setTimeout: () => 0, clearTimeout() {}, setInterval: () => 0, clearInterval() {},
    window: { addEventListener() {} },
    Vue: function (value) { options = value; },
  });
  const vm = { ...options.data, navigation, browserStorage: storage, copiedOrderIds, replacedUrls };
  for (const [name, method] of Object.entries(options.methods)) vm[name] = method.bind(vm);
  for (const [name, getter] of Object.entries(options.computed)) {
    Object.defineProperty(vm,name,{get:()=>getter.call(vm)});
  }
  return vm;
}

export function memoryStorage() {
  const values = new Map();
  return { getItem: key => values.get(key) || null,
    setItem: (key,value) => values.set(key,String(value)), removeItem: key => values.delete(key) };
}

export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
