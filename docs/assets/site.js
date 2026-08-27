(() => {
  const body = document.body;
  const languageButton = document.querySelector("[data-language-toggle]");
  const languageLabel = document.querySelector("[data-language-label]");
  const menuButton = document.querySelector("[data-menu-toggle]");
  const mobileNav = document.getElementById("mobile-nav");

  const saved = localStorage.getItem("orafit-language");
  const browserLanguage = (navigator.language || "en").toLowerCase();
  let language = saved === "ja" || saved === "en" ? saved : (browserLanguage.startsWith("ja") ? "ja" : "en");

  function applyLanguage(next) {
    language = next;
    body.dataset.lang = language;
    document.documentElement.lang = language;
    languageLabel.textContent = language === "ja" ? "EN" : "JA";
    languageButton.setAttribute("aria-label", language === "ja" ? "Switch to English" : "日本語に切り替える");
    localStorage.setItem("orafit-language", language);
  }

  applyLanguage(language);
  languageButton.addEventListener("click", () => applyLanguage(language === "ja" ? "en" : "ja"));

  menuButton.addEventListener("click", () => {
    const open = menuButton.getAttribute("aria-expanded") === "true";
    menuButton.setAttribute("aria-expanded", String(!open));
    mobileNav.hidden = open;
  });

  mobileNav.addEventListener("click", (event) => {
    if (event.target.closest("a")) {
      menuButton.setAttribute("aria-expanded", "false");
      mobileNav.hidden = true;
    }
  });

  document.querySelectorAll("[data-copy]").forEach((button) => {
    button.addEventListener("click", async () => {
      const target = document.querySelector(button.dataset.copy);
      if (!target || !navigator.clipboard) return;
      await navigator.clipboard.writeText(target.innerText);
      const original = button.innerHTML;
      button.textContent = language === "ja" ? "コピー済み" : "Copied";
      window.setTimeout(() => { button.innerHTML = original; }, 1200);
    });
  });
})();
