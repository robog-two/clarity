// Clarity's observation script, run by the browser pilot through evaluate_script in the isolated
// "clarity" sandbox realm (the page's own scripts cannot see or patch it). Called as
// observe(op, args); returns plain JSON. It never returns the value of a password field or of a
// field Clarity typed a secret into (args.secret lists their refs). Lines starting with // are
// stripped before sending.
(op, a) => {
  const S = window.__clarity || (window.__clarity = { n: 0, els: new Map(), refs: new WeakMap(), added: [], armed: null, obs: null });
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
    return o;
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
  const classify = (el) => {
    if (el.matches("script,style,noscript,template,svg,head,meta,link")) return null;
    const role = roleOf(el);
    if (INTERACTIVE.has(role) || role === "heading" || role === "alert" || role === "status" || role === "dialog" || role === "alertdialog") return role;
    if (role === "text") { const t = Array.from(el.childNodes).some((c) => c.nodeType === 3 && c.textContent.trim().length > 1); return t ? "text" : null; }
    return null;
  };
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
  const signals = () => {
    const t = (doc.body && doc.body.innerText || "").slice(0, 20000);
    const m = t.match(/[^.\n]{0,60}\b(we('ve| have)? (sent|texted|emailed)|code (was |has been )?(sent|resent)|enter (the|your) (\d-digit )?code|new code)\b[^.\n]{0,60}/gi);
    return (m || []).slice(0, 3).map((s) => clip(s, 120));
  };
  const page = () => ({ url: location.href, title: doc.title, vw, vh, scrollY: Math.round(window.scrollY), docH: Math.round(doc.documentElement.scrollHeight), opener: !!window.opener });

  if (op === "look") {
    const root = a.scope ? byRef(a.scope) : doc.body;
    if (!root) return { ...page(), error: "no element " + a.scope + " on this page any more" };
    const items = [];
    let total = 0;
    walk(root, (el) => {
      const role = classify(el);
      if (!role) return;
      if (!shown(el)) return role === "dialog" ? false : undefined;
      total++;
      if (items.length < (a.max || 400)) items.push(item(el, role));
      if (role === "button" || role === "link" || role === "heading") return false; // their children are their name
    });
    const f = fields();
    return { ...page(), dialog: dialogOf(), items, total, otp: !!f.otp, password: !!f.password, signals: signals() };
  }
  if (op === "point") {
    let el = doc.elementFromPoint(a.x, a.y), x = a.x, y = a.y;
    for (let i = 0; el && i < 8; i++) {
      if (el.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(x, y); if (inner && inner !== el) { el = inner; continue; } }
      if (el.tagName === "IFRAME") { try { const r = el.getBoundingClientRect(); const d = el.contentDocument; if (d) { x -= r.left; y -= r.top; const inner = d.elementFromPoint(x, y); if (inner) { el = inner; continue; } } } catch (e) {} }
      break;
    }
    if (!el) return { ...page(), hit: null };
    let cur = el, hit = null;
    for (let i = 0; cur && i < 8; i++) {
      const role = roleOf(cur);
      if (INTERACTIVE.has(role)) { hit = item(cur, role); break; }
      if (cur.tagName === "LABEL" && cur.control) { hit = item(cur.control, roleOf(cur.control)); break; }
      cur = cur.parentElement || (cur.getRootNode().host || null);
    }
    return { ...page(), hit: hit || item(el, roleOf(el) || "text") };
  }
  if (op === "target") {
    const el = byRef(a.ref);
    if (!el) return { ...page(), hit: null };
    if (a.scroll) { const r = rectOf(el); if (r.y < 0 || r.y + r.h > vh) el.scrollIntoView({ block: "center" }); }
    const token = "k" + Math.random().toString(36).slice(2, 10);
    el.setAttribute("data-clarity-ref", token);
    const unique = el.getRootNode() === doc && doc.querySelectorAll('[data-clarity-ref="' + token + '"]').length === 1;
    return { ...page(), hit: item(el, roleOf(el) || "text"), token: unique ? token : null };
  }
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
  if (op === "arm") {
    if (S.obs) S.obs.disconnect();
    S.added = []; S.armed = a.token;
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
  if (op === "changes") {
    const same = S.armed === a.token;
    const added = same ? Array.from(new Set(S.added)) : [];
    if (same) S.added = [];
    const f = fields();
    return { ...page(), same, added, dialog: dialogOf(), otp: !!f.otp, password: !!f.password, signals: signals() };
  }
  if (op === "signin") return { ...page(), ...fields() };
  if (op === "rects") return { rects: (a.refs || []).map(byRef).filter(Boolean).map(rectOf) };
  if (op === "read") {
    const root = a.scope ? byRef(a.scope) : doc.body;
    if (!root) return { text: "", error: "no element " + a.scope };
    const parts = [];
    let size = 0;
    const tw = (root.ownerDocument || doc).createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const keep = new Map();
    for (let n = tw.nextNode(); n && size < (a.max || 8000); n = tw.nextNode()) {
      const t = n.textContent.replace(/\s+/g, " ").trim();
      const p = n.parentElement;
      if (!t || !p || p.closest("script,style,noscript,template")) continue;
      let ok = keep.get(p); if (ok === undefined) { ok = shown(p); keep.set(p, ok); }
      if (!ok) continue;
      const field = p.closest("input,textarea,select");
      if (field) continue;
      parts.push(t); size += t.length + 1;
    }
    return { text: parts.join(" ").slice(0, a.max || 8000), truncated: size >= (a.max || 8000) };
  }
  if (op === "scroll") { window.scrollBy(0, (a.dy || 0) * vh * 0.8); return { scrollY: Math.round(window.scrollY) }; }
  return { error: "unknown op " + op };
}
