// Baked into the profile template; copied to a fresh tmpfs on every container start.
// Telemetry, studies, reports, updates: all off.
user_pref("toolkit.telemetry.enabled", false);
user_pref("toolkit.telemetry.unified", false);
user_pref("toolkit.telemetry.archive.enabled", false);
user_pref("datareporting.healthreport.uploadEnabled", false);
user_pref("datareporting.policy.dataSubmissionEnabled", false);
user_pref("app.shield.optoutstudies.enabled", false);
user_pref("browser.crashReports.unsubmittedCheck.autoSubmit2", false);
user_pref("browser.tabs.crashReporting.sendReport", false);
user_pref("app.update.enabled", false);
user_pref("app.update.auto", false);
user_pref("extensions.update.enabled", false);
user_pref("extensions.update.autoUpdateDefault", false);
// No first-run / what's-new pages, no default-browser nag, no prompts.
user_pref("browser.startup.homepage", "about:blank");
user_pref("startup.homepage_welcome_url", "");
user_pref("startup.homepage_welcome_url.additional", "");
user_pref("browser.startup.homepage_override.mstone", "ignore");
user_pref("browser.aboutwelcome.enabled", false);
user_pref("browser.shell.checkDefaultBrowser", false);
user_pref("browser.messaging-system.whatsNewPanel.enabled", false);
user_pref("browser.newtabpage.enabled", false);
user_pref("browser.tabs.warnOnClose", false);
// Restore the open tabs on start, so a restart (every deploy recreates the container, which kills
// Firefox: to Firefox that is a crash) doesn't drop the page a helper was on. geckodriver writes its
// own defaults (blank start page, no resume after a crash) over these, so entrypoint.sh also passes
// them as --pref; they stay here for a Firefox started by hand on this profile. Under WebDriver the
// tab selected at restore comes back empty, so firefox-wrapper.sh adds a spare one (session-prep.mjs).
user_pref("browser.startup.page", 3);
user_pref("browser.sessionstore.resume_from_crash", true);
// Never the "Restore session?" page instead of the tabs, however many unclean stops in a row; never
// the safe-mode prompt either (nobody is there to answer it).
user_pref("browser.sessionstore.max_resumed_crashes", 1000000);
// Load every restored tab right away: a tab waiting to be clicked has no browsing context yet, so
// WebDriver (list_pages) wouldn't see it.
user_pref("browser.sessionstore.restore_on_demand", false);
user_pref("browser.sessionstore.restore_tabs_lazily", false);
user_pref("toolkit.startup.max_resumed_crashes", -1);
// Cookies live in the kept profile, so a sign-in survives a restart. Session restore keeps only the
// tabs and their history, nothing extra on disk (privacy_level 2: no form data, no POST data, no
// session cookies, on any site): the agent types secrets into forms.
user_pref("browser.sessionstore.privacy_level", 2);
user_pref("network.cookie.lifetimePolicy", 0);
user_pref("privacy.sanitize.sanitizeOnShutdown", false);
// No password manager, form autofill, or credit cards.
user_pref("signon.rememberSignons", false);
user_pref("signon.autofillForms", false);
user_pref("extensions.formautofill.addresses.enabled", false);
user_pref("extensions.formautofill.creditCards.enabled", false);
// No background network chatter (the only traffic should be what the agent browses to).
user_pref("network.captive-portal-service.enabled", false);
user_pref("network.connectivity-service.enabled", false);
user_pref("browser.safebrowsing.malware.enabled", false);
user_pref("browser.safebrowsing.phishing.enabled", false);
user_pref("browser.safebrowsing.downloads.enabled", false);
user_pref("browser.search.update", false);
user_pref("browser.region.update.enabled", false);
user_pref("extensions.getAddons.cache.enabled", false);
user_pref("extensions.systemAddon.update.enabled", false);
user_pref("media.gmp-manager.url", "");
user_pref("browser.urlbar.suggest.quicksuggest.sponsored", false);
user_pref("browser.urlbar.quicksuggest.enabled", false);
user_pref("browser.search.suggest.enabled", false);
user_pref("app.normandy.enabled", false);
// Sideloaded add-ons in the profile's extensions/ directory are enabled without a prompt.
user_pref("extensions.autoDisableScopes", 0);
user_pref("extensions.enabledScopes", 15);
user_pref("extensions.postDownloadThirdPartyPrompt", false);
user_pref("xpinstall.signatures.required", true);
// Downloads go to one fixed place and never prompt. /downloads is a volume the sandbox reads
// (read-only) at /workspace/downloads.
user_pref("browser.download.folderList", 2);
user_pref("browser.download.dir", "/downloads");
user_pref("browser.download.useDownloadDir", true);
user_pref("browser.download.manager.addToRecentDocs", false);
user_pref("browser.helperApps.deleteTempFileOnExit", true);
// Nobody is there to answer "what should Firefox do with this file?": save new types right away,
// straight into the download dir (not a temp dir first), and never open them afterwards.
user_pref("browser.download.always_ask_before_handling_new_types", false);
user_pref("browser.download.start_downloads_in_tmp_dir", false);
user_pref("browser.download.open_pdfs_internally", false);
user_pref("browser.download.viewableInternally.enabledTypes", "");
// A PDF a site sends inline ("View statement") is saved as a download too, not opened in the
// built-in viewer: the agent works on documents in the sandbox (pdftotext), and the viewer gave it
// no way to get the file there. computers/browser/smoke.sh checks both kinds of PDF link.
user_pref("pdfjs.disabled", true);
// All traffic goes through the egress proxy (computers/egress), which refuses private, loopback and
// link-local destinations. The container has no other route out. No bypass list, not even
// localhost: the proxy refuses it anyway, and file:// pages do not use the proxy.
user_pref("network.proxy.type", 1);
user_pref("network.proxy.http", "egress");
user_pref("network.proxy.http_port", 3128);
user_pref("network.proxy.ssl", "egress");
user_pref("network.proxy.ssl_port", 3128);
user_pref("network.proxy.share_proxy_settings", true);
user_pref("network.proxy.socks", "");
user_pref("network.proxy.socks_port", 0);
user_pref("network.proxy.socks_remote_dns", true);
user_pref("network.proxy.no_proxies_on", "");
user_pref("network.proxy.failover_timeout", 5);
// WebRTC off so nothing (STUN/ICE) goes around the proxy.
user_pref("media.peerconnection.enabled", false);
// Cookie banners: Firefox rejects them itself where it has a rule, including its generic rules for
// common consent managers (1 = reject; 2 would accept when rejecting is not offered, which we never
// want). uBlock Origin's cookie-notice lists (policies.json) hide the rest.
user_pref("cookiebanners.service.mode", 1);
user_pref("cookiebanners.service.mode.privateBrowsing", 1);
user_pref("cookiebanners.service.enableGlobalRules", true);
user_pref("cookiebanners.service.enableGlobalRules.subFrames", true);
