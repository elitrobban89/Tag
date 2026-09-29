/* MiniPrisTåget — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
// Uppstartsskärm — glaskortet som ligger framför tåget i välkomstscenen.
//
// Samma mönster som systerprojektens splash (Elbilsladdning, Bankomat): statusrader som
// tickar in och tänds gröna, en typewriter-bootrad och en progress-stapel. Scenen bakom —
// soluppgången, stationen och tåget som rusar förbi — ligger kvar i index.html; det här
// skriptet lägger kortet ovanpå och bestämmer NÄR scenen lyfter.
//
// Siffrorna är RIKTIGA:
//   · Java, Spring Boot och Groq-modellen står i data-attribut på #mpt-intro (servern fyller
//     dem när sidan renderas) — de raderna behöver inte vänta på något anrop.
//   · Stationer, tåg i trafik, avgångar och punktlighet kommer från /api/splash, som frågar
//     Trafikverkets Open Data API (cachat en minut på servern).
// Svarar ett anrop inte står den beskrivande texten kvar på raden — hellre tyst än fel.
//
// Visas en gång per flik (samma regel som förut); ?splash=1 tvingar fram den.
(function () {
  'use strict';

  var intro = document.getElementById('mpt-intro');
  if (!intro) return;

  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var ds = intro.dataset || {};
  var live = null; // svaret från /api/splash

  function fmt(n) { return Math.round(n).toLocaleString('sv-SE'); }

  // Varje rad: ikon, rubrik, pill, animation och en text-funktion. e (0–1) driver
  // uppräkningen av talen när datan kommer, så siffrorna rullar upp i stället för att hoppa.
  var ROWS = [
    { ic: '☕', t: 'Java', tag: 'STARTAD', an: 'anga', tx: function () {
        if (!ds.java) return 'JVM startad';
        return '<b>Java ' + ds.java + '</b>' + (ds.boot ? ' \xb7 Spring Boot ' + ds.boot : '') + ' \xb7 Thymeleaf';
      } },
    { ic: '🤖', t: 'Groq API', tag: 'ONLINE', an: 'robot', tx: function () {
        return ds.model ? '<b>' + ds.model + '</b> \xb7 svarar p\xe5 Groq LPU' : 'Spr\xe5kmodell startad';
      } },
    { ic: '📡', t: 'Trafikverket Open Data API', tag: 'LIVE', an: 'signal', tx: function (e) {
        if (!live || !live.stationer) return 'Realtidsdata f\xf6r hela j\xe4rnv\xe4gen';
        return '<b>' + fmt(live.stationer * e) + '</b> stationer \xb7 realtidsdata';
      } },
    { ic: '🚆', t: 'T\xe5g i trafik', tag: 'LIVE', an: 'tag', tx: function (e) {
        if (!live || live.tagITrafik == null) return 'R\xe4knar t\xe5gen p\xe5 sp\xe5ret…';
        var s = '<b>' + fmt(live.tagITrafik * e) + '</b> t\xe5g rullar just nu';
        if (live.bolag) s += ' \xb7 <b>' + fmt(live.bolag * e) + '</b> t\xe5gbolag';
        return s;
      } },
    { ic: '🕐', t: 'Avg\xe5ngar', an: 'klocka', tx: function (e) {
        if (!live || live.avgangarNastaTimme == null) return 'Tidtabeller f\xf6r alla bolag';
        return '<b>' + fmt(live.avgangarNastaTimme * e) + '</b> avg\xe5ngar kommande timmen';
      } },
    { ic: '⏱️', t: 'Punktlighet', an: 'tid', tx: function (e) {
        if (!live || live.punktlighet == null) return 'Förseningar &amp; inst\xe4llda i realtid';
        var s = '<b>' + fmt(live.punktlighet * e) + ' %</b> i tid senaste halvtimmen';
        if (live.installda) s += ' \xb7 ' + fmt(live.installda * e) + ' inst\xe4llda';
        return s;
      } },
    { ic: '💺', t: 'Platskartor', an: 'plats', tx: function (e) {
        if (!live || !live.vagnsskisser) return 'V\xe4lj din egen plats i vagnen';
        return '<b>' + fmt(live.vagnsskisser * e) + '</b> vagnsskisser \xb7 v\xe4lj din plats';
      } },
    { ic: '🐳', t: 'Drift', an: 'val', tx: function () {
        return 'Docker \xb7 Liberica OpenJDK' + (ds.java ? ' ' + ds.java.split('.')[0] : '') + ' \xb7 Render';
      } }
  ];
  var LIVE_ROWS = [2, 3, 4, 5, 6];

  var BOOT = ['startar Spring Boot', 'ansluter till Trafikverket', 'r\xe4knar t\xe5gen i trafik', 'fr\xe5gar Groq om b\xe4sta resan'];

  function injectStyles() {
    if (document.getElementById('mpt-sp-style')) return;
    var css = document.createElement('style');
    css.id = 'mpt-sp-style';
    css.textContent = [
      // Kortet ersätter den gamla rubriken och ligger ovanför horisonten, framför scenen.
      '.mpt-intro .mpt-intro-inner{top:0;bottom:auto;display:flex;justify-content:center;',
        'padding:clamp(14px,4vh,44px) 14px 0;}',
      '.mpt-sp-card{position:relative;width:100%;max-width:404px;padding:22px 20px 18px;border-radius:24px;',
        'display:flex;flex-direction:column;align-items:center;text-align:center;',
        'background:radial-gradient(120% 50% at 50% -6%,rgba(96,165,250,.26),transparent 68%),',
          'linear-gradient(165deg,rgba(14,30,64,.78),rgba(8,17,40,.84));',
        '-webkit-backdrop-filter:blur(18px) saturate(150%);backdrop-filter:blur(18px) saturate(150%);',
        'border:1px solid rgba(147,197,253,.24);',
        'box-shadow:0 26px 70px rgba(0,0,0,.45),inset 0 1px 0 rgba(255,255,255,.16),0 0 60px rgba(59,130,246,.16);',
        "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;",
        'animation:mptFade .55s ease both;}',
      '.mpt-sp-card .mpt-welcome{font-size:11px;letter-spacing:4px;}',
      '.mpt-sp-card .mpt-brand{margin-top:4px;font-size:clamp(28px,7vw,40px);}',
      '.mpt-sp-card .mpt-groq{margin-top:10px;}',
      '.mpt-sp-boot{font-family:ui-monospace,SFMono-Regular,"Cascadia Code",Consolas,monospace;',
        'font-size:.72rem;color:rgba(191,219,254,.85);margin:12px 0 14px;min-height:1.2em;}',
      '.mpt-sp-boot .pr{color:#4ade80;font-weight:700;margin-right:5px;}',
      '.mpt-sp-cur{display:inline-block;width:7px;height:.95em;background:#4ade80;margin-left:3px;',
        'vertical-align:-1px;animation:mpt-sp-blink 1s steps(1) infinite;}',
      '.mpt-sp-rows{width:100%;display:flex;flex-direction:column;gap:6px;}',
      '.mpt-sp-row{display:flex;align-items:center;gap:11px;text-align:left;padding:8px 12px;border-radius:12px;',
        'background:rgba(147,197,253,.08);border:1px solid rgba(147,197,253,.18);',
        'opacity:0;transform:translateY(8px);',
        'transition:opacity .35s ease,transform .35s ease,border-color .3s,background .3s,box-shadow .3s;}',
      '.mpt-sp-row.show{opacity:1;transform:translateY(0);}',
      '.mpt-sp-row.done{border-color:rgba(52,211,153,.45);background:rgba(34,197,94,.12);',
        'box-shadow:0 0 18px rgba(34,197,94,.16);}',
      '.mpt-sp-ic{font-size:1.02rem;flex-shrink:0;width:22px;text-align:center;display:inline-block;',
        'filter:grayscale(.7) brightness(.85);opacity:.8;transition:filter .5s,opacity .5s;}',
      '.mpt-sp-row.done .mpt-sp-ic{filter:drop-shadow(0 0 6px rgba(125,211,252,.55));opacity:1;',
        'animation:mpt-ic-gung 2.6s ease-in-out var(--ikd,0s) infinite;}',
      '.mpt-sp-row.done .mpt-ic-tag{animation:mpt-ic-tag 1.6s ease-in-out var(--ikd,0s) infinite;}',
      '.mpt-sp-row.done .mpt-ic-signal{animation:mpt-ic-signal 1.8s ease-in-out var(--ikd,0s) infinite;}',
      '.mpt-sp-row.done .mpt-ic-klocka,.mpt-sp-row.done .mpt-ic-tid{animation:mpt-ic-tick 2s steps(4,end) var(--ikd,0s) infinite;}',
      '@keyframes mpt-ic-gung{0%,100%{transform:translateY(0) rotate(0);}50%{transform:translateY(-1.5px) rotate(-4deg);}}',
      '@keyframes mpt-ic-tag{0%,100%{transform:translateX(-2px);}50%{transform:translateX(3px);}}',
      '@keyframes mpt-ic-signal{0%,100%{transform:scale(.95);opacity:.8;}45%{transform:scale(1.14);opacity:1;}}',
      '@keyframes mpt-ic-tick{from{transform:rotate(-8deg);}to{transform:rotate(8deg);}}',
      '.mpt-sp-tx{flex:1;min-width:0;display:flex;flex-direction:column;line-height:1.25;}',
      '.mpt-sp-tx>b{font-size:.8rem;font-weight:700;color:#f4f8ff;display:flex;align-items:center;gap:7px;}',
      '.mpt-sp-tx i{font-size:.68rem;font-style:normal;color:rgba(191,219,254,.82);',
        'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
      '.mpt-sp-tx i b{color:#7dd3fc;font-weight:800;}',
      '.mpt-sp-onl{display:inline-flex;align-items:center;gap:4px;padding:1px 7px 1px 5px;border-radius:20px;',
        'font-size:.5rem;font-weight:800;letter-spacing:.1em;color:#6ee7b7;',
        'background:rgba(34,197,94,.14);border:1px solid rgba(34,197,94,.4);}',
      '.mpt-sp-onl.live{color:#7dd3fc;background:rgba(59,130,246,.14);border-color:rgba(59,130,246,.4);}',
      '.mpt-sp-onl .dot{width:5px;height:5px;border-radius:50%;background:currentColor;',
        'box-shadow:0 0 6px currentColor;animation:mpt-sp-pulse 1.4s ease-in-out infinite;}',
      '.mpt-sp-st{flex-shrink:0;width:20px;height:20px;display:flex;align-items:center;justify-content:center;}',
      '.mpt-sp-spin{width:14px;height:14px;border-radius:50%;border:2px solid rgba(59,130,246,.2);',
        'border-top-color:#60a5fa;animation:mpt-sp-spin .6s linear infinite;}',
      '.mpt-sp-check{width:19px;height:19px;border-radius:50%;background:rgba(34,197,94,.18);',
        'border:1px solid rgba(34,197,94,.55);color:#4ade80;font-size:11px;font-weight:900;',
        'display:flex;align-items:center;justify-content:center;animation:mpt-sp-pop .3s ease;}',
      // Stapeln är en räls: slipers under, fyllningen glider fram som ett tåg.
      '.mpt-sp-bar{position:relative;width:100%;height:8px;margin-top:16px;border-radius:4px;overflow:hidden;',
        'background:repeating-linear-gradient(90deg,rgba(150,112,74,.35) 0 5px,transparent 5px 14px),rgba(255,255,255,.05);',
        'border:1px solid rgba(147,197,253,.3);}',
      '.mpt-sp-fill{height:100%;width:0;border-radius:3px;background:linear-gradient(90deg,#3b82f6,#22c55e);',
        'box-shadow:0 0 12px rgba(34,197,94,.55);transition:width .55s ease;}',
      '.mpt-sp-pct{margin-top:8px;font-size:.64rem;font-weight:700;letter-spacing:.08em;',
        'color:rgba(147,197,253,.7);font-family:ui-monospace,Consolas,monospace;}',
      // Render-brickan i foten: loggans pil lyfter om och om igen, som en deploy.
      '.mpt-sp-render{display:inline-flex;align-items:center;gap:7px;margin-top:10px;padding:4px 11px 4px 5px;border-radius:20px;',
        'background:rgba(139,92,246,.12);border:1px solid rgba(167,139,250,.35);font-size:.64rem;color:rgba(221,214,254,.9);}',
      '.mpt-sp-render b{color:#fff;font-weight:700;}',
      '.mpt-sp-render i{font-style:normal;font-family:ui-monospace,Consolas,monospace;color:#c4b5fd;opacity:.85;}',
      '.rd-logo{display:block;border-radius:6px;box-shadow:0 0 12px rgba(139,92,246,.55);}',
      '.rd-pil{animation:rd-lyft 1.6s cubic-bezier(.4,0,.2,1) infinite;}',
      '@keyframes rd-lyft{0%{transform:translateY(2px);opacity:.3;}45%{transform:translateY(-1px);opacity:1;}100%{transform:translateY(-3px);opacity:0;}}',
      '.mpt-intro.mpt-sp-ready .mpt-sp-boot,.mpt-intro.mpt-sp-ready .mpt-sp-pct{color:#6ee7b7;}',
      '.mpt-sp-skip{position:absolute;top:12px;right:14px;z-index:6;background:rgba(255,255,255,.08);',
        'border:1px solid rgba(255,255,255,.16);color:rgba(255,255,255,.7);font-size:.68rem;font-weight:600;',
        'padding:4px 11px;border-radius:20px;cursor:pointer;font-family:inherit;}',
      '.mpt-sp-skip:hover{background:rgba(255,255,255,.16);color:#fff;}',
      '@keyframes mpt-sp-spin{to{transform:rotate(360deg);}}',
      '@keyframes mpt-sp-blink{0%,100%{opacity:1;}50%{opacity:0;}}',
      '@keyframes mpt-sp-pop{0%{transform:scale(.4);opacity:0;}60%{transform:scale(1.15);}100%{transform:scale(1);opacity:1;}}',
      '@keyframes mpt-sp-pulse{0%,100%{opacity:1;}50%{opacity:.45;}}',
      '@media (max-width:520px){',
        '.mpt-sp-card{padding:16px 13px 13px;border-radius:20px;}',
        '.mpt-sp-card .mpt-groq{margin-top:8px;}',
        '.mpt-sp-boot{margin:9px 0 10px;}',
        '.mpt-sp-rows{gap:4px;}.mpt-sp-row{padding:6px 11px;}',
        '.mpt-sp-tx>b{font-size:.76rem;}.mpt-sp-tx i{font-size:.65rem;}',
        '.mpt-sp-bar{margin-top:11px;}',
      '}',
      '@media (prefers-reduced-motion:reduce){.mpt-sp-card *{animation:none!important;transition:none!important;}}'
    ].join('');
    document.head.appendChild(css);
  }

  // Render-loggan: moln med en pil som lyfter — koden som åker från GitHub upp i drift.
  function renderLogo(id) {
    return '<svg class="rd-logo" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">' +
      '<defs><linearGradient id="' + id + '" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#a78bfa"/><stop offset="1" stop-color="#4f46e5"/></linearGradient></defs>' +
      '<rect width="24" height="24" rx="6" fill="url(#' + id + ')"/>' +
      '<path d="M7.6 17h8.8a3.1 3.1 0 0 0 .5-6.15A4.6 4.6 0 0 0 8.1 9.7 3.6 3.6 0 0 0 7.6 17Z" fill="rgba(255,255,255,.22)" stroke="#fff" stroke-width="1.2"/>' +
      '<path class="rd-pil" d="M12 15.4v-4.6m0 0-2 2m2-2 2 2" stroke="#fff" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" fill="none"/>' +
    '</svg>';
  }

  function tagHtml(tag) {
    if (!tag) return '';
    return '<span class="mpt-sp-onl' + (tag === 'LIVE' ? ' live' : '') + '"><span class="dot"></span>' + tag + '</span>';
  }

  function buildCard() {
    var inner = intro.querySelector('.mpt-intro-inner');
    if (!inner) return null;
    // Rubriken och Groq-brickan flyttas in i kortet i stället för att dupliceras.
    var head = inner.innerHTML;
    var rows = ROWS.map(function (r, i) {
      return '<div class="mpt-sp-row" data-i="' + i + '">' +
        '<span class="mpt-sp-ic mpt-ic-' + r.an + '" style="--ikd:' + (i * 0.14).toFixed(2) + 's">' + r.ic + '</span>' +
        '<span class="mpt-sp-tx"><b>' + r.t + tagHtml(r.tag) + '</b><i class="mpt-sp-sub">' + r.tx(1) + '</i></span>' +
        '<span class="mpt-sp-st"><span class="mpt-sp-spin"></span></span>' +
      '</div>';
    }).join('');
    inner.innerHTML =
      '<div class="mpt-sp-card">' + head +
        '<p class="mpt-sp-boot"><span class="pr">▸</span><span class="mpt-sp-boot-tx"></span><span class="mpt-sp-cur"></span></p>' +
        '<div class="mpt-sp-rows">' + rows + '</div>' +
        '<div class="mpt-sp-bar"><div class="mpt-sp-fill"></div></div>' +
        '<div class="mpt-sp-pct">0% ombord</div>' +
        '<div class="mpt-sp-render">' + renderLogo('mptRd') + '<span>Autodeploy via <b>Render</b></span><i class="mpt-sp-deploy">GitHub → master</i></div>' +
      '</div>';
    var skip = document.createElement('button');
    skip.type = 'button';
    skip.className = 'mpt-sp-skip';
    skip.textContent = 'Hoppa \xf6ver ✕';
    intro.appendChild(skip);
    return skip;
  }

  function sub(i) { return intro.querySelector('.mpt-sp-row[data-i="' + i + '"] .mpt-sp-sub'); }

  function rakna(i) {
    var el = sub(i);
    if (!el) return;
    if (reduce) { el.innerHTML = ROWS[i].tx(1); return; }
    var start = performance.now();
    (function step(now) {
      var p = Math.min(1, (now - start) / 1100);
      el.innerHTML = ROWS[i].tx(1 - Math.pow(1 - p, 3));
      if (p < 1) requestAnimationFrame(step);
    })(start);
  }

  function startBoot(el) {
    var pi = 0, ci = 0, mode = 'type', stopped = false;
    function tick() {
      if (stopped) return;
      var phrase = BOOT[pi];
      if (mode === 'type') {
        el.textContent = phrase.slice(0, ++ci);
        if (ci >= phrase.length) { mode = 'erase'; setTimeout(tick, 850); return; }
        setTimeout(tick, 38);
      } else {
        ci -= 2; if (ci < 0) ci = 0;
        el.textContent = phrase.slice(0, ci);
        if (ci <= 0) { pi = (pi + 1) % BOOT.length; mode = 'type'; }
        setTimeout(tick, 20);
      }
    }
    tick();
    return function (txt) { stopped = true; el.textContent = txt; };
  }

  injectStyles();
  var skip = buildCard();
  var fill = intro.querySelector('.mpt-sp-fill');
  var pctEl = intro.querySelector('.mpt-sp-pct');
  var bootTx = intro.querySelector('.mpt-sp-boot-tx');
  var cursor = intro.querySelector('.mpt-sp-cur');
  var rows = intro.querySelectorAll('.mpt-sp-row');
  var stopBoot = reduce ? function (t) { bootTx.textContent = t; } : startBoot(bootTx);
  var timers = [];
  var animKlar = false, finished = false;

  function setPct(p) {
    if (fill) fill.style.width = Math.round(p) + '%';
    if (pctEl) pctEl.textContent = Math.round(p) + '% ombord';
  }

  // Datan från Trafikverket. Rader som redan visas räknar upp på plats; rader som inte
  // tickat in än får talen när de dyker upp.
  fetch('/api/splash', { cache: 'no-store' })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (d) {
      if (!d || finished) return;
      live = d;
      if (d.java) ds.java = d.java;
      if (d.springBoot) ds.boot = d.springBoot;
      if (d.groqModel) ds.model = d.groqModel;
      var dep = intro.querySelector('.mpt-sp-deploy');
      if (dep && d.deployCommit) dep.textContent = (d.deployBranch ? d.deployBranch + ' · ' : '') + d.deployCommit;
      [0, 1, 7].forEach(function (i) { var el = sub(i); if (el) el.innerHTML = ROWS[i].tx(1); });
      LIVE_ROWS.forEach(rakna);
      if (animKlar) finish();
    })
    .catch(function () {});

  function finish() {
    if (finished) return;
    finished = true;
    timers.forEach(clearTimeout);
    intro.classList.add('mpt-sp-ready');
    stopBoot('alla ombord — trevlig resa ✓');
    if (cursor) cursor.style.display = 'none';
    rows.forEach(function (row) {
      row.classList.add('show', 'done');
      row.querySelector('.mpt-sp-st').innerHTML = '<span class="mpt-sp-check">✓</span>';
    });
    setPct(100);
    try { sessionStorage.setItem('mpt_welcomed', '1'); } catch (e) {}
    setTimeout(function () {
      intro.classList.add('mpt-ut');
      setTimeout(function () {
        if (intro.parentNode) intro.parentNode.removeChild(intro);
        document.documentElement.style.overflow = '';
      }, 620);
    }, reduce ? 400 : 1100);
  }

  // Animationen fyller till 92 %. Resten är "Trafikverket svarade" — kommer svaret aldrig
  // släpper taket ändå fram appen: en sida man kan använda slår en splash man inte kan lämna.
  var ANIM_PCT = 92, TAK_MS = 9000;
  function animationKlar() {
    animKlar = true;
    if (live) return finish();
    stopBoot('v\xe4ntar p\xe5 Trafikverket…');
    timers.push(setTimeout(finish, Math.max(0, TAK_MS - (performance.now() - t0))));
  }

  var t0 = performance.now();
  if (skip) skip.addEventListener('click', finish);

  if (reduce) {
    rows.forEach(function (row) {
      row.classList.add('show', 'done');
      row.querySelector('.mpt-sp-st').innerHTML = '<span class="mpt-sp-check">✓</span>';
    });
    setPct(ANIM_PCT);
    timers.push(setTimeout(animationKlar, 2000));
    return;
  }

  var START = 500, STAGGER = 380, FLIP = 300;
  rows.forEach(function (row, i) {
    var appear = START + i * STAGGER;
    timers.push(setTimeout(function () { row.classList.add('show'); }, appear));
    timers.push(setTimeout(function () {
      row.classList.add('done');
      row.querySelector('.mpt-sp-st').innerHTML = '<span class="mpt-sp-check">✓</span>';
      setPct((i + 1) / rows.length * ANIM_PCT);
      if (i === rows.length - 1) timers.push(setTimeout(animationKlar, 450));
    }, appear + FLIP));
  });
})();
