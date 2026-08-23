(() => {
  'use strict';

  const body = document.body;
  const drawer = document.getElementById('app-drawer');
  const toggle = document.querySelector('[data-drawer-toggle]');
  const closeControls = document.querySelectorAll('[data-drawer-close]');
  const searchControl = document.querySelector('[data-page-search]');
  const desktopQuery = window.matchMedia('(min-width: 981px)');
  const storageKey = 'ownerguard.drawer.collapsed';

  const focusPageSearch = () => {
    const field = document.querySelector('input[name="q"], input[type="search"]');
    if (!field) return;
    field.scrollIntoView({ behavior: 'smooth', block: 'center' });
    window.setTimeout(() => {
      field.focus();
      if (typeof field.select === 'function') field.select();
    }, 180);
  };

  if (searchControl) searchControl.addEventListener('click', focusPageSearch);

  document.addEventListener('keydown', (event) => {
    const target = event.target;
    const typing = target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement || (target instanceof HTMLElement && target.isContentEditable);
    if (event.key === '/' && !typing && searchControl) {
      event.preventDefault();
      focusPageSearch();
      return;
    }
    if (event.key === 'Escape' && body.classList.contains('drawer-open') && drawer && toggle) {
      body.classList.remove('drawer-open', 'drawer-lock');
      toggle.setAttribute('aria-expanded', 'false');
      toggle.focus();
    }
  });

  if (!drawer || !toggle) return;

  const setExpanded = (expanded) => {
    toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
  };

  const openMobile = () => {
    body.classList.add('drawer-open', 'drawer-lock');
    setExpanded(true);
    const first = drawer.querySelector('.drawer-link');
    if (first) window.setTimeout(() => first.focus(), 80);
  };

  const closeMobile = () => {
    body.classList.remove('drawer-open', 'drawer-lock');
    setExpanded(false);
  };

  const applyDesktopPreference = () => {
    body.classList.remove('drawer-open', 'drawer-lock');
    const collapsed = window.localStorage.getItem(storageKey) === '1';
    body.classList.toggle('drawer-collapsed', collapsed);
    setExpanded(!collapsed);
  };

  const handleToggle = () => {
    if (desktopQuery.matches) {
      const collapsed = !body.classList.contains('drawer-collapsed');
      body.classList.toggle('drawer-collapsed', collapsed);
      window.localStorage.setItem(storageKey, collapsed ? '1' : '0');
      setExpanded(!collapsed);
      return;
    }
    if (body.classList.contains('drawer-open')) closeMobile();
    else openMobile();
  };

  toggle.addEventListener('click', handleToggle);
  closeControls.forEach((control) => control.addEventListener('click', closeMobile));
  drawer.querySelectorAll('a').forEach((link) => {
    link.addEventListener('click', () => {
      if (!desktopQuery.matches) closeMobile();
    });
  });
  desktopQuery.addEventListener('change', () => {
    if (desktopQuery.matches) applyDesktopPreference();
    else {
      body.classList.remove('drawer-collapsed', 'drawer-open', 'drawer-lock');
      setExpanded(false);
    }
  });

  if (desktopQuery.matches) applyDesktopPreference();
  else setExpanded(false);
})();
