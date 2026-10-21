(() => {
  const headerBar = document.getElementById('headerBar');

  const onScroll = () => {
    headerBar?.classList.toggle('is-scrolled', window.scrollY > 8);
  };
  onScroll();
  window.addEventListener('scroll', onScroll, { passive: true });

  // Subtle star field in the hero, echoing the official page canvas.
  const canvas = document.getElementById('heroCanvas');
  if (canvas && canvas.getContext) {
    const ctx = canvas.getContext('2d');
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let width = 0;
    let height = 0;
    let dpr = 1;
    let stars = [];
    const makeStars = () => {
      const count = Math.min(120, Math.round((width * height) / 14000));
      stars = Array.from({ length: count }, () => ({
        x: Math.random() * width,
        y: Math.random() * height,
        r: Math.random() * 1.1 + 0.3,
        v: Math.random() * 0.08 + 0.02,
        o: Math.random() * 0.35 + 0.08,
      }));
    };
    const resize = () => {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      width = canvas.clientWidth;
      height = canvas.clientHeight;
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      makeStars();
    };
    const draw = () => {
      ctx.clearRect(0, 0, width, height);
      for (const s of stars) {
        ctx.beginPath();
        ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(255,255,255,${s.o})`;
        ctx.fill();
      }
    };
    const tick = () => {
      for (const s of stars) {
        s.y += s.v;
        if (s.y > height + 2) { s.y = -2; s.x = Math.random() * width; }
      }
      draw();
      requestAnimationFrame(tick);
    };
    resize();
    window.addEventListener('resize', resize);
    if (reduce) {
      draw();
    } else {
      tick();
    }
  }

  // Gentle entrance for key blocks.
  if ('IntersectionObserver' in window) {
    const targets = document.querySelectorAll('.hero-head, .hero-preview, .section-heading, .cap-card:not(.cap-card-motion), .mode-card, .showcase-card, .join-inner');
    targets.forEach((el) => el.classList.add('reveal'));
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add('in');
            io.unobserve(entry.target);
          }
        }
      },
      { threshold: 0.08, rootMargin: '0px 0px -5% 0px' },
    );
    targets.forEach((el) => io.observe(el));
  }
  // Mobile menu.
  const menu = document.getElementById('mobileMenu');
  const menuToggle = document.getElementById('menuToggle');
  const menuClose = document.getElementById('menuClose');
  const setMenu = (open) => {
    if (!menu) return;
    menu.classList.toggle('is-open', open);
    menu.setAttribute('aria-hidden', String(!open));
    menuToggle?.setAttribute('aria-expanded', String(open));
    document.body.style.overflow = open ? 'hidden' : '';
  };
  menuToggle?.addEventListener('click', () => setMenu(true));
  menuClose?.addEventListener('click', () => setMenu(false));
  menu?.querySelectorAll('a').forEach((a) => a.addEventListener('click', () => setMenu(false)));
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') setMenu(false);
  });

  // Language switch: the Chinese page lives at /, English at /en.html.
  document.querySelectorAll('[data-locale]').forEach((button) => {
    button.addEventListener('click', () => {
      const locale = button.getAttribute('data-locale');
      if (locale === 'en') window.location.href = './en.html';
      else window.location.href = './';
    });
  });

  // Copy interconnect plugin install command.
  const copyInstall = document.getElementById('copyInstallCmd');
  const installCmd = document.getElementById('pluginInstallCmd');
  copyInstall?.addEventListener('click', async () => {
    const value = installCmd?.textContent || '';
    const original = copyInstall.textContent;
    try { await navigator.clipboard.writeText(value); copyInstall.textContent = '已复制'; }
    catch { copyInstall.textContent = '复制失败'; }
    setTimeout(() => { copyInstall.textContent = original; }, 1600);
  });

  // Join section: particles scatter by default and converge as the section
  // reaches the middle of the viewport, spreading again when scrolling away.
  const joinCanvas = document.getElementById('joinCanvas');
  const joinSection = joinCanvas ? joinCanvas.closest('.join') : null;
  if (joinCanvas && joinSection && joinCanvas.getContext) {
    const jctx = joinCanvas.getContext('2d');
    const jReduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let jw = 0, jh = 0, jdpr = 1, pts = [];
    let gather = 0;

    const makePoints = () => {
      const n = Math.min(150, Math.max(70, Math.round((jw * jh) / 9000)));
      const cx = jw * 0.5;
      const cy = jh * 0.46;
      const ringRadius = Math.min(jw * 0.23, jh * 0.38);
      pts = Array.from({ length: n }, () => {
        // Cluster target: a solid circle (filled disk), not a hollow ring.
        const angle = Math.random() * Math.PI * 2;
        const radius = ringRadius * Math.sqrt(Math.random()) * (0.92 + Math.random() * 0.08);
        const clusterX = cx + Math.cos(angle) * radius;
        const clusterY = cy + Math.sin(angle) * radius;
        const outAng = Math.random() * Math.PI * 2;
        const outDist = (0.35 + Math.random() * 0.55) * Math.max(jw, jh) * 0.62;
        return {
          clusterX, clusterY,
          scatterX: cx + Math.cos(outAng) * outDist,
          scatterY: cy + Math.sin(outAng) * outDist * 0.55,
          x: 0, y: 0,
          r: Math.random() * 1.1 + 0.35,
          a: Math.random() * 0.45 + 0.12,
          phase: Math.random() * Math.PI * 2,
          drift: Math.random() * 2.4 + 0.8,
          speed: Math.random() * 0.0005 + 0.0002,
          ix: 0, iy: 0, ivx: 0, ivy: 0,
        };
      });
    };

    const jresize = () => {
      jdpr = Math.min(window.devicePixelRatio || 1, 2);
      jw = joinCanvas.clientWidth;
      jh = joinCanvas.clientHeight;
      joinCanvas.width = Math.round(jw * jdpr);
      joinCanvas.height = Math.round(jh * jdpr);
      jctx.setTransform(jdpr, 0, 0, jdpr, 0, 0);
      makePoints();
    };

    const scrollGather = () => {
      const rect = joinSection.getBoundingClientRect();
      const vh = window.innerHeight || 1;
      const center = rect.top + rect.height * 0.5;
      const distance = Math.abs(center - vh * 0.5);
      const raw = Math.max(0, Math.min(1, 1 - distance / (vh * 0.85)));
      return raw * raw * (3 - 2 * raw);
    };

    const jdraw = (t = 0) => {
      jctx.clearRect(0, 0, jw, jh);
      const target = scrollGather();
      gather += (target - gather) * (jReduce ? 1 : 0.07);

      for (const p of pts) {
        const wobble = jReduce ? 0 : Math.sin(t * p.speed + p.phase) * p.drift;
        p.x = p.scatterX + (p.clusterX - p.scatterX) * gather + wobble;
        p.y = p.scatterY + (p.clusterY - p.scatterY) * gather + wobble;
        p.ix += p.ivx; p.iy += p.ivy;
        p.ivx *= 0.94; p.ivy *= 0.94;
        p.ix *= 0.975; p.iy *= 0.975;
        p.x += p.ix; p.y += p.iy;
      }

      for (const p of pts) {
        jctx.beginPath();
        jctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        jctx.fillStyle = 'rgba(205,222,255,' + p.a * (0.55 + gather * 0.45) + ')';
        jctx.fill();
      }
    };

    const jtick = (t) => {
      jdraw(t || 0);
      requestAnimationFrame(jtick);
    };

    jresize();
    window.addEventListener('resize', jresize);

    // Clicking empty particle space bursts the field outward from the pointer;
    // it settles back into the scroll-driven cluster afterwards.
    if (!jReduce) {
      joinSection.addEventListener('pointerdown', (event) => {
        if (event.target.closest('a, button, input, select, textarea')) return;
        const rect = joinCanvas.getBoundingClientRect();
        const cx = event.clientX - rect.left;
        const cy = event.clientY - rect.top;
        for (const p of pts) {
          let dx = p.x - cx;
          let dy = p.y - cy;
          if (dx === 0 && dy === 0) { dx = Math.random() - 0.5; dy = Math.random() - 0.5; }
          const dist = Math.hypot(dx, dy) || 1;
          const force = Math.max(0, 240 - dist) / 240 * 55;
          p.ivx += (dx / dist) * force;
          p.ivy += (dy / dist) * force;
        }
      });
    }
    if (jReduce) jdraw(0); else requestAnimationFrame(jtick);
  }

  // Animated capability cards: reveal on scroll, pause offscreen/hover, reduced-motion fallback.
  const motionCards = Array.from(document.querySelectorAll('[data-motion-card]'));
  if (motionCards.length) {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const isEnglish = document.documentElement.lang === 'en';
    motionCards.forEach((card) => {
      const toggle = card.querySelector('[data-motion-toggle]');
      let visible = false;
      let userPaused = false;

      const sync = () => {
        const isVisible = visible || reduceMotion.matches;
        card.classList.toggle('is-inview', isVisible);
        const running = isVisible && !userPaused && !reduceMotion.matches;
        card.classList.toggle('is-running', running);
        card.classList.toggle('is-paused', userPaused || reduceMotion.matches);
        if (toggle) {
          toggle.setAttribute('aria-label', userPaused ? (isEnglish ? 'Play motion' : '播放动效') : (isEnglish ? 'Pause motion' : '暂停动效'));
          toggle.setAttribute('aria-pressed', String(userPaused));
        }
      };

      const observer = new IntersectionObserver((entries) => {
        for (const entry of entries) visible = entry.isIntersecting;
        sync();
      }, { threshold: 0.25 });
      observer.observe(card);

      toggle?.addEventListener('click', () => {
        userPaused = !userPaused;
        sync();
      });
      reduceMotion.addEventListener('change', sync);
      sync();

      document.addEventListener('visibilitychange', () => {
        if (document.hidden) card.classList.remove('is-running');
        else sync();
      });
    });
  }
})();
