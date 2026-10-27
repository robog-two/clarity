// Clarity's observation script, run by the browser pilot through evaluate_script in the isolated
// "clarity" sandbox realm (the page's own scripts cannot see or patch it). Called as
// observe(op, args); returns plain JSON. It never returns the value of a password field or of a
// field Clarity typed a secret into (args.secret lists their refs), nor any cookie value. Lines
// starting with // are stripped before sending. evaluate_script takes at most 16 KiB, so each call
// sends only the parts its op needs: lines between "//#if op1 op2" and "//#end" go only with those ops.
(op, a) => {
  const S = window.__clarity || (window.__clarity = { n: 0, els: new Map(), refs: new WeakMap(), added: [], armed: null, obs: null, mut: 0, net: 0, mo: null, act: 0 });
  const doc = document;
  const vw = window.innerWidth, vh = window.innerHeight;
  const secret = new Set(a.secret || []);
  const INTERACTIVE = new Set(["button", "link", "checkbox", "radio", "tab", "menuitem", "menuitemcheckbox", "menuitemradio", "option", "switch", "combobox", "textbox", "searchbox", "slider", "spinbutton", "listbox", "treeitem"]);
  const ref = (el) => {
    let r = S.refs.get(el);
    if (!r) { r = "c" + (++S.n); S.refs.set(el, r); S.els.set(r, new WeakRef(el)); }
    return r;
  };
  const byRef = (r) => { const w = S.els.get(r); const el = w && w.deref(); return el && el.isConnected ? el : null; };
  const clip = (s, n) => { s = (s || "").replace(/\s+/g, " ").trim(); return s.length > n ? s.slice(0, n - 1) + "…" : s; };
  const offset = (el) => { let x = 0, y = 0, w = el.ownerDocument.defaultView; while (w && w.frameElement) { const r = w.frameElement.getBoundingClientRect(); x += r.left; y += r.top; w = w.parent; } return { x, y }; };
  const rectOf = (el) => { const r = el.getBoundingClientRect(), o = offset(el); return { x: Math.round(r.left + o.x), y: Math.round(r.top + o.y), w: Math.round(r.width), h: Math.round(r.height) }; };
  const shown = (el) => {
    if (!el.getClientRects().length) return false;
    if (el.checkVisibility && !el.checkVisibility({ opacityProperty: true, visibilityProperty: true })) return false;
    const r = rectOf(el), st = el.ownerDocument.defaultView.getComputedStyle(el);
    if (r.w < 4 || r.h < 4) return false;
    if (r.x + r.w <= 0 || r.y + r.h + window.scrollY <= 0) return false; // pushed off the page to the left or top
    if (parseFloat(st.fontSize) < 6 && !el.matches("input,select,textarea,img")) return false;
    if (st.clipPath && st.clipPath !== "none" && /inset\(50%|circle\(0/.test(st.clipPath)) return false;
    return true;
  };
  // An element whose content scrolls on its own (an inner list in a fixed layout), not the page.
  const SC = new Map();
  const isScroller = (n) => {
    if (!n || n.nodeType !== 1 || n === doc.body || n === doc.documentElement) return false;
    let s = SC.get(n);
    if (s === undefined) { s = n.clientHeight >= 60 && n.scrollHeight > n.clientHeight + 16 && /auto|scroll|overlay/.test(n.ownerDocument.defaultView.getComputedStyle(n).overflowY); SC.set(n, s); }
    return s;
  };
  const scrollerOf = (el) => { for (let n = el.parentElement; n; n = n.parentElement) if (isScroller(n)) return n; return null; };
  const roleOf = (el) => {
    const explicit = (el.getAttribute("role") || "").split(" ")[0];
    if (explicit) return explicit;
    const t = el.tagName.toLowerCase();
    if (t === "a" && el.hasAttribute("href")) return "link";
    if (t === "button" || t === "summary") return "button";
    if (t === "select") return el.multiple ? "listbox" : "combobox";
    if (t === "textarea") return "textbox";
    if (/^h[1-6]$/.test(t)) return "heading";
    if (t === "dialog") return "dialog";
    if (t === "input") {
      const ty = (el.type || "text").toLowerCase();
      if (["button", "submit", "reset", "image"].includes(ty)) return "button";
      if (ty === "checkbox" || ty === "radio") return ty;
      if (ty === "search") return "searchbox";
      if (ty === "range") return "slider";
      if (ty === "number") return "spinbutton";
      if (ty === "hidden") return "";
      return "textbox";
    }
    if (el.isContentEditable && el.parentElement && !el.parentElement.isContentEditable) return "textbox";
    if (el.hasAttribute("onclick") || (el.tabIndex >= 0 && el.hasAttribute("tabindex"))) return "button";
    if (t === "p") return "text";
    return "";
  };
  const textOf = (el) => clip(el.innerText || el.textContent, 120);
  const nameOf = (el) => {
    const by = el.getAttribute("aria-labelledby");
    if (by) { const s = by.split(/\s+/).map((id) => el.getRootNode().getElementById?.(id)).filter(Boolean).map(textOf).join(" "); if (s) return s; }
    const al = el.getAttribute("aria-label"); if (al && al.trim()) return al;
    if (el.labels && el.labels.length) { const s = Array.from(el.labels).map((l) => clip(l.innerText, 120)).join(" "); if (s) return s; }
    if (el.tagName === "INPUT" && ["submit", "button", "reset"].includes(el.type)) return el.value;
    if (el.tagName === "IMG") return el.alt;
    const t = el.matches("input,select,textarea") ? "" : textOf(el);
    return t || el.getAttribute("placeholder") || el.getAttribute("title") || el.getAttribute("alt") || "";
  };
  // Every element under root, through open shadow roots and same-origin frames, in document order.
  const walk = (root, visit) => {
    const stack = [root];
    while (stack.length) {
      const node = stack.pop();
      if (node.nodeType === 1) {
        if (visit(node) === false) continue;
        if (node.tagName === "IFRAME") { try { const d = node.contentDocument; if (d && d.body) stack.push(d.body); } catch (e) {} }
        if (node.shadowRoot) stack.push(...Array.from(node.shadowRoot.children).reverse());
      }
      if (node.children) stack.push(...Array.from(node.children).reverse());
    }
  };
  const page = () => ({ url: location.href, title: doc.title, vw, vh, scrollY: Math.round(window.scrollY), docH: Math.round(doc.documentElement.scrollHeight), opener: !!window.opener });
  // A scroll area's name: its label, the heading just before it (or its parent's), or a heading inside it.
  const areaName = (el) => {
    const l = el.getAttribute("aria-label") || el.getAttribute("title"); if (l) return clip(l, 50);
    const by = el.getAttribute("aria-labelledby"); if (by) { const t = el.ownerDocument.getElementById(by.split(/\s+/)[0]); if (t) return clip(t.innerText, 50); }
    const H = "h1,h2,h3,h4,h5,h6,[role=heading],legend,label";
    for (let n = el, i = 0; n && i < 3; n = n.parentElement, i++) for (let p = n.previousElementSibling; p; p = p.previousElementSibling) { const h = p.matches(H) ? p : p.querySelector(H); if (h && h.innerText.trim()) return clip(h.innerText, 50); }
    const h = el.querySelector(H); return h ? clip(h.innerText, 50) : clip(el.innerText, 30);
  };
  // How much of an area shows: rows in view, rows in all (estimated for a virtualized list from its height), pixels below.
  const area = (el) => {
    let list = el; for (let i = 0; i < 6 && list.children.length === 1; i++) list = list.children[0];
    const kids = Array.from(list.children).filter((c) => c.getClientRects().length), b = el.getBoundingClientRect();
    const hs = kids.map((c) => c.getBoundingClientRect().height).filter((h) => h > 4).sort((x, y) => x - y);
    const rowH = hs.length ? hs[hs.length >> 1] : 0;
    const seen = kids.filter((c) => { const k = c.getBoundingClientRect(); return k.bottom > b.top + 2 && k.top < b.bottom - 2; }).length;
    return { ref: ref(el), name: areaName(el), ...rectOf(el), rows: seen, total: Math.max(kids.length, rowH ? Math.round(el.scrollHeight / rowH) : 0), top: Math.round(el.scrollTop), below: Math.max(0, Math.round(el.scrollHeight - el.scrollTop - el.clientHeight)) };
  };
//#if look point target signin changes
  const stateOf = (el) => {
    const s = [];
    if (el.checked || el.getAttribute("aria-checked") === "true") s.push("checked");
    if (el.disabled || el.getAttribute("aria-disabled") === "true") s.push("disabled");
    const ex = el.getAttribute("aria-expanded"); if (ex) s.push(ex === "true" ? "expanded" : "collapsed");
    if (el.getAttribute("aria-selected") === "true" || (el.tagName === "OPTION" && el.selected)) s.push("selected");
    if (el.required) s.push("required");
    if (el.getAttribute("aria-invalid") === "true") s.push("invalid");
    if (el.getRootNode() !== el.ownerDocument) s.push("shadow");
    if (el.ownerDocument !== doc) s.push("frame");
    return s;
  };
  const valueOf = (el, r) => {
    if (!el.matches("input,select,textarea")) return null;
    if (el.type === "password") return el.value ? "•••" : "";
    if (secret.has(r)) return "[secret, hidden]";
    if (el.tagName === "SELECT") return clip(el.selectedOptions[0]?.textContent, 60);
    if (["checkbox", "radio", "submit", "button", "reset"].includes(el.type)) return null;
    return clip(el.value, 60);
  };
  const item = (el, role) => {
    const r = ref(el), rect = rectOf(el);
    const own = role === "text" ? Array.from(el.childNodes).filter((c) => c.nodeType === 3).map((c) => c.textContent).join(" ") : nameOf(el);
    const o = { ref: r, role, name: clip(own, 80), states: stateOf(el), x: rect.x, y: rect.y, w: rect.w, h: rect.h };
    const v = valueOf(el, r); if (v !== null && v !== "") o.value = v;
    const ac = el.getAttribute("autocomplete"); if (ac) o.ac = ac;
    if (el.tagName === "INPUT") o.type = (el.type || "text").toLowerCase();
    if (role === "link" && el.href) o.href = clip(el.href, 100);
    if (role === "heading") o.level = parseInt(el.tagName.slice(1)) || parseInt(el.getAttribute("aria-level")) || null;
    o.inView = rect.y + rect.h > 0 && rect.y < vh && rect.x < vw;
    const sc = scrollerOf(el);
    if (sc) { const b = rectOf(sc); o.area = ref(sc); if (rect.y + rect.h <= b.y || rect.y >= b.y + b.h) o.inView = false; }
    return o;
  };
//#end
//#if look changes signin
  const dialogOf = () => {
    let found = null;
    walk(doc.body, (el) => {
      if (found) return false;
      const role = roleOf(el);
      if ((el.tagName === "DIALOG" && el.open) || role === "alertdialog" || (role === "dialog" && el.getAttribute("aria-modal") === "true")) {
        if (shown(el)) { const h = el.querySelector("h1,h2,h3,[role=heading]"); found = clip(el.getAttribute("aria-label") || (h && h.innerText) || el.innerText, 80); }
      }
    });
    return found;
  };
  const isOtp = (el) => el.tagName === "INPUT" && el.type !== "hidden" && (el.autocomplete === "one-time-code" ||
    /(^|[^a-z])(otp|one.?time|verification|2fa|mfa|passcode|security.?code|sms.?code|auth.?code)([^a-z]|$)/i.test([el.name, el.id, el.getAttribute("aria-label"), el.placeholder].join(" ")) ||
    (el.inputMode === "numeric" && el.maxLength >= 4 && el.maxLength <= 8));
  const fields = () => {
    const out = { user: null, password: null, otp: null, submit: null, passwords: 0 };
    let form = null;
    walk(doc.body, (el) => {
      if (el.tagName !== "INPUT" && el.tagName !== "BUTTON") return;
      if (!shown(el) || el.disabled) return;
      const ty = (el.type || "text").toLowerCase();
      if (el.tagName === "INPUT" && isOtp(el)) { out.otp = out.otp || item(el, "textbox"); form = form || el.form; return; }
      if (ty === "password") { out.passwords++; out.password = out.password || item(el, "textbox"); form = form || el.form; return; }
      if (el.tagName === "INPUT" && (["username", "email"].includes(el.autocomplete) || ty === "email" || (["text", "tel"].includes(ty) && /user|email|login|account|phone/i.test(el.name + " " + el.id)))) {
        out.user = out.user || item(el, "textbox"); form = form || el.form;
      }
    });
    const buttons = [];
    walk(doc.body, (el) => { if (roleOf(el) === "button" && shown(el) && !el.disabled) buttons.push(el); });
    const submit = buttons.find((b) => form && b.form === form && (b.type === "submit" || b.tagName === "BUTTON" && !b.getAttribute("type"))) ||
      buttons.find((b) => /^(sign in|log ?in|continue|next|verify|submit|confirm)$/i.test(clip(nameOf(b), 40)));
    if (submit) out.submit = item(submit, "button");
    return out;
  };
//#end
//#if look changes
  const signals = () => {
    const t = (doc.body && doc.body.innerText || "").slice(0, 20000);
    const m = t.match(/[^.\n]{0,60}\b(we('ve| have)? (sent|texted|emailed)|code (was |has been )?(sent|resent)|enter (the|your) (\d-digit )?code|new code)\b[^.\n]{0,60}/gi);
    return { signals: (m || []).slice(0, 3).map((s) => clip(s, 120)), expired: /session (has )?(expired|timed out)|you('ve| have) been (signed|logged) out|please (sign|log) in again/i.test(t) };
  };
  // Signs of being signed in: a sign-out control or a greeting on screen, and the names (never the values) of the page's readable cookies.
  const ACCOUNT = /^((sign|log) ?out|my account|(hi|hello|hey|welcome back),? \p{L}[\p{L}'.-]{1,30}!?)$/iu;
  const account = () => {
    const tw = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
    for (let n = tw.nextNode(), i = 0; n && i < 5000; n = tw.nextNode(), i++) {
      const t = n.textContent.replace(/\s+/g, " ").trim();
      if (t.length > 2 && t.length < 40 && ACCOUNT.test(t) && n.parentElement && shown(n.parentElement)) return clip(t, 40);
    }
    for (const el of doc.querySelectorAll("[aria-label]")) { const l = el.getAttribute("aria-label").trim(); if (l.length < 40 && ACCOUNT.test(l) && shown(el)) return clip(l, 40); }
    return null;
  };
  const ckn = () => { try { const s = doc.cookie.split(";").map((c) => c.split("=")[0].trim()).filter(Boolean).sort().join(";"); let h = 7; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return (s ? s.split(";").length : 0) + ":" + (h >>> 0).toString(36); } catch (e) { return null; } };
  const below = () => Math.max(0, Math.round(doc.documentElement.scrollHeight - window.scrollY - vh));
//#end
//#if look
  const classify = (el) => {
    if (el.matches("script,style,noscript,template,svg,head,meta,link")) return null;
    const role = roleOf(el);
    if (INTERACTIVE.has(role) || role === "heading" || role === "alert" || role === "status" || role === "dialog" || role === "alertdialog") return role;
    if (role === "text") { const t = Array.from(el.childNodes).some((c) => c.nodeType === 3 && c.textContent.trim().length > 1); return t ? "text" : null; }
    return null;
  };
  if (op === "look") {
    const root = a.scope ? byRef(a.scope) : doc.body;
    if (!root) return { ...page(), error: "no element " + a.scope + " on this page any more" };
    const items = [], areas = [];
    let total = 0;
    walk(root, (el) => {
      if (areas.length < 8 && isScroller(el) && shown(el)) areas.push(area(el));
      const role = classify(el);
      if (!role) return;
      if (!shown(el)) return role === "dialog" ? false : undefined;
      total++;
      if (items.length < (a.max || 400)) items.push(item(el, role));
      if (role === "button" || role === "link" || role === "heading") return false; // their children are their name
    });
    const f = fields();
    return { ...page(), dialog: dialogOf(), items, total, otp: !!f.otp, password: !!f.password, ...signals(), account: account(), ckn: ckn(), below: below(), areas };
  }
//#end
//#if point scroll
  // The deepest element at a viewport point, through open shadow roots and same-origin frames.
  const topAt = (x0, y0) => {
    let el = doc.elementFromPoint(x0, y0), x = x0, y = y0;
    for (let i = 0; el && i < 8; i++) {
      if (el.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(x, y); if (inner && inner !== el) { el = inner; continue; } }
      if (el.tagName === "IFRAME") { try { const r = el.getBoundingClientRect(); const d = el.contentDocument; if (d) { x -= r.left; y -= r.top; const inner = d.elementFromPoint(x, y); if (inner) { el = inner; continue; } } } catch (e) {} }
      break;
    }
    return el;
  };
  const up = (n) => n.parentElement || (n.getRootNode().host || null);
//#end
//#if point
  const within = (top, el) => { for (let n = top; n; n = up(n)) if (n === el) return true; return false; };
  // What a click on el would really hit: its opacity with its ancestors', visibility, whether it paints
  // anything itself, the control underneath it, and whether it is on top at its own centre.
  const facts = (el, x, y) => {
    let opacity = 1;
    for (let n = el; n && n.nodeType === 1; n = up(n)) opacity *= parseFloat(n.ownerDocument.defaultView.getComputedStyle(n).opacity) || 0;
    const st = el.ownerDocument.defaultView.getComputedStyle(el);
    const clear = (c) => c === "transparent" || /rgba\([^)]*,\s*0(\.0+)?\)$/.test(c);
    const border = ["Top", "Right", "Bottom", "Left"].some((s) => parseFloat(st["border" + s + "Width"]) > 0 && st["border" + s + "Style"] !== "none" && !clear(st["border" + s + "Color"]));
    const painted = el.matches("input,select,textarea,img,video,canvas,svg") || st.backgroundImage !== "none" || !clear(st.backgroundColor) ||
      border || st.boxShadow !== "none" || (el.innerText || "").trim().length > 0 || !!el.querySelector("img,svg,canvas,video,picture");
    let under = null;
    for (const e of doc.elementsFromPoint(x, y)) {
      if (e === el || within(e, el) || within(el, e)) continue;
      if (INTERACTIVE.has(roleOf(e))) { const r = rectOf(e); under = r.w * r.h; break; }
    }
    const r = rectOf(el);
    const top = topAt(r.x + r.w / 2, r.y + r.h / 2);
    return { opacity, hidden: st.visibility === "hidden", painted, under, onTop: !!top && within(top, el) };
  };
  if (op === "point") {
    const el = topAt(a.x, a.y);
    if (!el) return { ...page(), hit: null };
    let cur = el, hit = null, target = el;
    for (let i = 0; cur && i < 8; i++) {
      const role = roleOf(cur);
      if (INTERACTIVE.has(role)) { hit = item(cur, role); target = cur; break; }
      if (cur.tagName === "LABEL" && cur.control) { hit = item(cur.control, roleOf(cur.control)); target = cur.control; break; }
      cur = up(cur);
    }
    hit = hit || item(el, roleOf(el) || "text");
    hit.paint = facts(target, a.x, a.y);
    return { ...page(), hit };
  }
//#end
//#if target
  if (op === "target") {
    const el = byRef(a.ref);
    if (!el) return { ...page(), hit: null };
    if (a.scroll) { const r = rectOf(el); if (r.y < 0 || r.y + r.h > vh) el.scrollIntoView({ block: "center" }); }
    const token = "k" + Math.random().toString(36).slice(2, 10);
    el.setAttribute("data-clarity-ref", token);
    const unique = el.getRootNode() === doc && doc.querySelectorAll('[data-clarity-ref="' + token + '"]').length === 1;
    return { ...page(), hit: item(el, roleOf(el) || "text"), token: unique ? token : null };
  }
//#end
//#if focus
  if (op === "focus") {
    const el = byRef(a.ref);
    if (!el) return { ok: false };
    if (a.clear && "value" in el && el.tagName !== "SELECT") el.value = "";
    el.focus();
    let act = doc.activeElement;
    for (let i = 0; act && i < 8; i++) {
      if (act.shadowRoot && act.shadowRoot.activeElement) act = act.shadowRoot.activeElement;
      else if (act.tagName === "IFRAME") { try { act = act.contentDocument.activeElement; } catch (e) { break; } }
      else break;
    }
    return { ok: act === el };
  }
//#end
//#if arm
  if (op === "arm") {
    if (S.obs) S.obs.disconnect();
    S.added = []; S.armed = a.token; S.act = performance.now();
    S.obs = new MutationObserver((records) => {
      for (const r of records) {
        const nodes = r.type === "characterData" ? [r.target.parentElement] : Array.from(r.addedNodes);
        for (const n of nodes) {
          const el = n && (n.nodeType === 1 ? n : n.parentElement);
          if (!el || !el.isConnected || !shown(el)) continue;
          const t = clip(n.nodeType === 1 ? n.innerText : n.textContent, 160);
          if (t.length > 1 && S.added.length < 30) S.added.push(t);
        }
      }
    });
    S.obs.observe(doc.documentElement, { subtree: true, childList: true, characterData: true });
    return { armed: true };
  }
//#end
//#if changes
  if (op === "changes") {
    const same = S.armed === a.token;
    const added = same ? Array.from(new Set(S.added)) : [];
    if (same) S.added = [];
    const f = fields();
    return { ...page(), same, added, dialog: dialogOf(), otp: !!f.otp, password: !!f.password, ...signals(), account: account(), ckn: ckn(), below: below() };
  }
//#end
//#if signin
  if (op === "signin") return { ...page(), ...fields() };
//#end
//#if rects
  if (op === "rects") return { rects: (a.refs || []).map(byRef).filter(Boolean).map(rectOf) };
//#end
//#if read
  // Visible text; with a.lines, one entry per block (a row of a list stays one line), skipping the areas in a.skip.
  if (op === "read") {
    const root = a.scope ? byRef(a.scope) : doc.body;
    if (!root) return { text: "", error: "no element " + a.scope };
    const skip = (a.skip || []).map(byRef).filter(Boolean);
    const parts = [], keep = new Map(), blocks = new Map(), lines = [];
    let size = 0;
    const blockOf = (p) => { for (let n = p; n && n !== root; n = n.parentElement) { const d = n.ownerDocument.defaultView.getComputedStyle(n).display; if (!/^inline|^contents/.test(d)) return n; } return root; };
    const tw = (root.ownerDocument || doc).createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n = tw.nextNode(); n && size < (a.max || 8000); n = tw.nextNode()) {
      const t = n.textContent.replace(/\s+/g, " ").trim();
      const p = n.parentElement;
      if (!t || !p || p.closest("script,style,noscript,template")) continue;
      let ok = keep.get(p); if (ok === undefined) { ok = shown(p) && !skip.some((s) => s.contains(p)); keep.set(p, ok); }
      if (!ok) continue;
      const field = p.closest("input,textarea,select");
      if (field) continue;
      parts.push(t); size += t.length + 1;
      if (a.lines) { const b = blockOf(p); let i = blocks.get(b); if (i === undefined) { i = lines.length; blocks.set(b, i); lines.push(t); } else lines[i] += " " + t; }
    }
    return { text: parts.join(" ").slice(0, a.max || 8000), truncated: size >= (a.max || 8000), ...(a.lines ? { lines } : {}) };
  }
//#end
//#if scroll
  // Scrolls an area (by ref, or the one under a point) or, with neither, the area under the viewport centre that
  // can still move that way, else the page (a.page: the page only). a.dy is in screens (+ down); a.to "top"
  // jumps to the top, a.top to that many pixels from it.
  if (op === "scroll") {
    const dir = a.to === "top" ? -1 : Math.sign(a.dy || 1);
    const can = (n) => isScroller(n) && (dir > 0 ? n.scrollTop + n.clientHeight < n.scrollHeight - 2 : n.scrollTop > 0);
    const go = (t, by) => { if (a.top != null) t.scrollTo({ top: a.top, behavior: "instant" }); else if (a.to === "top") t.scrollTo({ top: 0, behavior: "instant" }); else t.scrollBy({ top: (a.dy || 0) * by * 0.8, behavior: "instant" }); };
    let el = a.ref ? byRef(a.ref) : null;
    if (!el && !a.ref && !a.page) { const x = a.x != null ? a.x : vw / 2, y = a.x != null ? a.y : vh / 2; for (let n = topAt(x, y); n; n = up(n)) if (a.x != null ? isScroller(n) : can(n)) { el = n; break; } }
    if (a.ref && !el) return { error: "no element " + a.ref };
    if (el) {
      const t0 = el.scrollTop; S.act = performance.now();
      go(el, el.clientHeight);
      return { ...area(el), moved: Math.round(el.scrollTop - t0), scrollY: Math.round(window.scrollY) };
    }
    const y0 = window.scrollY; S.act = performance.now();
    go(window, vh);
    return { moved: Math.round(window.scrollY - y0), scrollY: Math.round(window.scrollY), below: Math.max(0, Math.round(doc.documentElement.scrollHeight - window.scrollY - vh)) };
  }
//#end
//#if reveal
  // Brings an element into view, scrolling the areas around it too.
  if (op === "reveal") { const el = byRef(a.ref); if (!el) return { ok: false }; S.act = performance.now(); el.scrollIntoView({ block: "center", inline: "nearest", behavior: "instant" }); return { ok: true }; }
//#end
//#if settle
  // Has the page settled? Ready state, time since the DOM last changed and since a request last finished
  // (resource timing; the page's own fetch is never wrapped), and any loading indicator on screen.
  if (op === "settle") {
    const now = performance.now();
    if (!S.mo) {
      S.mut = now;
      S.mo = new MutationObserver(() => { S.mut = performance.now(); });
      S.mo.observe(doc.documentElement, { subtree: true, childList: true, characterData: true });
      for (const e of performance.getEntriesByType("resource")) S.net = Math.max(S.net, e.responseEnd);
      try { new PerformanceObserver((l) => { for (const e of l.getEntries()) S.net = Math.max(S.net, e.responseEnd || e.startTime); }).observe({ type: "resource" }); } catch (e) {}
    }
    let busy = null;
    const q = doc.querySelectorAll('[aria-busy="true"],[role="progressbar"],[class*="spinner" i],[class*="loading" i],[class*="loader" i],[class*="skeleton" i],[class*="shimmer" i],[id*="spinner" i],[id*="loading" i]');
    for (let i = 0; i < q.length && i < 60 && !busy; i++) {
      const el = q[i];
      if (!shown(el)) continue;
      const r = rectOf(el);
      if (r.y + r.h > 0 && r.y < vh && r.w * r.h < vw * vh * 0.9) busy = clip(el.getAttribute("aria-label") || el.innerText || el.getAttribute("role") || String(el.className), 40) || "a loading indicator";
    }
    return { url: location.href, ready: doc.readyState, mutAgo: Math.round(now - S.mut), netAgo: Math.round(now - S.net), actAgo: Math.round(now - S.act), busy };
  }
//#end
  return { error: "unknown op " + op };
}
