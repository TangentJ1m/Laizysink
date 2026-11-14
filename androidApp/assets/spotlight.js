(() => {
  if (window.LaizysinkSpotlight) return;

  let active = null;

  const clear = () => {
    if (active) active.dispose();
    active = null;
    document.getElementById('__laizysink_spotlight')?.remove();
  };

  const show = (selector, tooltip) => {
    clear();

    let target;
    try {
      target = document.querySelector(selector);
    } catch (_) {
      return 'invalid-selector';
    }
    if (!target) return 'selector-missing';

    const overlay = document.createElement('div');
    overlay.id = '__laizysink_spotlight';
    overlay.setAttribute('aria-hidden', 'true');
    Object.assign(overlay.style, {
      position: 'fixed',
      inset: '0',
      zIndex: '2147483646',
      pointerEvents: 'none',
      overflow: 'hidden',
    });

    const panels = Array.from({ length: 4 }, () => {
      const panel = document.createElement('div');
      Object.assign(panel.style, {
        position: 'fixed',
        background: 'rgba(4,6,12,.68)',
        backdropFilter: 'blur(4px)',
        WebkitBackdropFilter: 'blur(4px)',
        pointerEvents: 'none',
      });
      overlay.appendChild(panel);
      return panel;
    });

    const pill = document.createElement('div');
    pill.textContent = tooltip;
    Object.assign(pill.style, {
      position: 'fixed',
      maxWidth: 'min(320px, calc(100vw - 24px))',
      background: '#161922',
      color: '#f3f4f6',
      border: '1px solid #6366f1',
      borderRadius: '16px',
      padding: '10px 14px',
      font: '14px sans-serif',
      boxShadow: '0 4px 24px #0008',
      boxSizing: 'border-box',
      pointerEvents: 'none',
    });
    overlay.appendChild(pill);
    document.documentElement.appendChild(overlay);

    const setRect = (element, left, top, width, height) => {
      Object.assign(element.style, {
        left: `${left}px`,
        top: `${top}px`,
        width: `${Math.max(0, width)}px`,
        height: `${Math.max(0, height)}px`,
      });
    };

    const update = () => {
      if (!target.isConnected) {
        clear();
        return;
      }

      const rect = target.getBoundingClientRect();
      const viewportWidth = window.innerWidth;
      const viewportHeight = window.innerHeight;
      const left = Math.max(0, Math.min(viewportWidth, rect.left));
      const top = Math.max(0, Math.min(viewportHeight, rect.top));
      const right = Math.max(left, Math.min(viewportWidth, rect.right));
      const bottom = Math.max(top, Math.min(viewportHeight, rect.bottom));

      setRect(panels[0], 0, 0, viewportWidth, top);
      setRect(panels[1], 0, top, left, bottom - top);
      setRect(panels[2], right, top, viewportWidth - right, bottom - top);
      setRect(panels[3], 0, bottom, viewportWidth, viewportHeight - bottom);

      const pillTop = top >= 60 ? top - 52 : Math.min(viewportHeight - 48, bottom + 12);
      const pillLeft = Math.max(12, Math.min(left, viewportWidth - 332));
      pill.style.left = `${pillLeft}px`;
      pill.style.top = `${Math.max(12, pillTop)}px`;
    };

    let animationFrame = 0;
    const scheduleUpdate = () => {
      if (animationFrame === 0) animationFrame = requestAnimationFrame(() => {
        animationFrame = 0;
        update();
      });
    };

    const visualViewport = window.visualViewport;
    window.addEventListener('scroll', scheduleUpdate, true);
    window.addEventListener('resize', scheduleUpdate, { passive: true });
    visualViewport?.addEventListener('scroll', scheduleUpdate, { passive: true });
    visualViewport?.addEventListener('resize', scheduleUpdate, { passive: true });

    const resizeObserver = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(scheduleUpdate);
    resizeObserver?.observe(target);

    const mutationObserver = new MutationObserver((mutations) => {
      if (mutations.some((mutation) => !overlay.contains(mutation.target))) scheduleUpdate();
    });
    mutationObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class', 'hidden', 'open', 'style'],
      childList: true,
      subtree: true,
    });

    const dispose = () => {
      if (animationFrame !== 0) cancelAnimationFrame(animationFrame);
      window.removeEventListener('scroll', scheduleUpdate, true);
      window.removeEventListener('resize', scheduleUpdate);
      visualViewport?.removeEventListener('scroll', scheduleUpdate);
      visualViewport?.removeEventListener('resize', scheduleUpdate);
      resizeObserver?.disconnect();
      mutationObserver.disconnect();
    };
    active = { dispose };

    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
    update();
    scheduleUpdate();
    return 'shown';
  };

  window.LaizysinkSpotlight = { show, clear };
})();
