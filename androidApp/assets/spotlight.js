(() => {
  const clear = () => document.getElementById('__laizysink_spotlight')?.remove();
  const show = (selector, tooltip) => {
    clear();
    const target = document.querySelector(selector);
    if (!target) return;
    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const rect = target.getBoundingClientRect();
    const overlay = document.createElement('div');
    overlay.id = '__laizysink_spotlight';
    Object.assign(overlay.style, { position: 'fixed', inset: '0', zIndex: '2147483646', pointerEvents: 'none', backdropFilter: 'blur(4px)', background: 'rgba(4,6,12,.68)', clipPath: `polygon(0 0,100% 0,100% 100%,0 100%,0 0,${rect.left}px ${rect.top}px,${rect.left}px ${rect.bottom}px,${rect.right}px ${rect.bottom}px,${rect.right}px ${rect.top}px,${rect.left}px ${rect.top}px)` });
    const pill = document.createElement('div');
    pill.textContent = tooltip;
    Object.assign(pill.style, { position: 'fixed', left: `${Math.max(12, rect.left)}px`, top: `${Math.max(12, rect.top - 48)}px`, background: '#161922', color: '#f3f4f6', border: '1px solid #6366f1', borderRadius: '16px', padding: '10px 14px', font: '14px sans-serif', boxShadow: '0 4px 24px #0008' });
    overlay.appendChild(pill);
    document.documentElement.appendChild(overlay);
  };
  window.LaizysinkSpotlight = { show, clear };
})();
