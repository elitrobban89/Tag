/* Tag — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
(function () {
  var TRAIN_CHAT_API = window.TRAIN_API_URL || "";
  var trainChatHistory = (function(){ try{ return JSON.parse(localStorage.getItem('tc-chat')||'[]'); }catch(e){ return []; } })();
  var _focusedDepContext = null;
  var _focusedDep = null;
  var tcExpanded = (function(){ try{ return localStorage.getItem('tc-chat-max') === '1'; }catch(e){ return false; } })();

  // Intercept _trainSearchData to enable live context update + auto-chip
  var _searchDataVal = null;
  Object.defineProperty(window, '_trainSearchData', {
    get: function() { return _searchDataVal; },
    set: function(val) {
      _searchDataVal = val;
      _focusedDepContext = null;
      _focusedDep = null;
      updateContextBar();
      if (val && val.departures && val.departures.length > 0) showSearchChip(val);
    },
    configurable: true
  });

  function showSearchChip(data) {
    var existing = document.getElementById('tc-search-chip');
    if (existing) existing.remove();
    var chip = document.createElement('div');
    chip.id = 'tc-search-chip';
    chip.className = 'tc-search-chip';
    chip.innerHTML = '<span class="tc-search-chip-icon">🚂</span><span>' + data.fromName + ' → ' + data.toName + '<br><span class="tc-search-chip-sub">' + data.departures.length + ' avgångar — fråga AI</span></span>';
    chip.addEventListener('click', function() { chip.remove(); if (!tcIsOpen()) tcSetOpen(true); });
    setTimeout(function() { if (chip.parentNode) chip.remove(); }, 8000);
    document.body.appendChild(chip);
  }

  function tcSaveChatHistory() {
    try { localStorage.setItem('tc-chat', JSON.stringify(trainChatHistory.slice(-20))); } catch(e) {}
  }

  function buildDepartureContext(data) {
    if (!data || !data.departures || data.departures.length === 0) return null;
    var lines = ["Sökresultat: " + data.fromName + " → " + data.toName + ", " + data.date];
    data.departures.forEach(function (d, i) {
      if (d.canceled) return;
      var seats = d.seatsLeft > 0 ? d.seatsLeft + " platser kvar" : "inga MiniPris-platser";
      var price = d.price || "okänt pris";
      var time  = d.travelMinutes ? Math.floor(d.travelMinutes / 60) + "h" + (d.travelMinutes % 60 ? (d.travelMinutes % 60) + "m" : "") : "";
      lines.push((i + 1) + ". " + d.departureTime + " (" + (d.operator || "okänd operatör") + ") — " + price + " — " + seats + (time ? " — restid " + time : ""));
    });
    if (data.returnDepartures && data.returnDepartures.length > 0) {
      lines.push("\nReturavgångar: " + data.toName + " → " + data.fromName + ", " + data.returnDate);
      data.returnDepartures.forEach(function (d, i) {
        if (d.canceled) return;
        var seats = d.seatsLeft > 0 ? d.seatsLeft + " platser kvar" : "inga MiniPris-platser";
        var price = d.price || "okänt pris";
        lines.push((i + 1) + ". " + d.departureTime + " (" + (d.operator || "") + ") — " + price + " — " + seats);
      });
    }
    return lines.join("\n");
  }

  // ── Snabbknapparna ────────────────────────────────────────────────────────
  // Samma form som bilrådgivningens: varje knapp bär en egen ton (--ton, RGB-tripplett)
  // och ikonen står i en rund bricka. Fyra likadana blå piller sa ingenting om vart de
  // ledde; färgen bär information nu. Klicken fångas av delegeringen på #tc-quick.
  function tcAttr(s) {
    return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
  }
  function tcChip(ton, ik, label, q) {
    return '<button class="tc-quick-btn" style="--ton:' + ton + '" data-q="' + tcAttr(q) + '">' +
      '<span class="tc-quick-ik">' + ik + '</span><span>' + tcAttr(label) + '</span></button>';
  }
  function tcStartChips() {
    return tcChip('251,191,36', '💰', 'Billigast', 'Vilken avgång är billigast?') +
      tcChip('56,189,248', '⚡', 'Snabbast', 'Vilken avgång är snabbast?') +
      tcChip('52,211,153', '🎫', 'Platser kvar', 'Vilka avgångar har MiniPris-platser kvar?') +
      tcChip('167,139,250', '🤖', 'Ge råd', 'Ge mig råd om vilken avgång jag ska välja');
  }

  function initTrainChat() {
    var style = document.createElement("style");
    style.textContent = `
      .tc-fab-wrap {
        position:fixed;bottom:24px;right:24px;z-index:9999;
        display:flex;flex-direction:column;align-items:center;gap:6px;
      }
      .tc-fab-label {
        background:rgba(59,130,246,0.15);border:1px solid rgba(96,165,250,0.4);
        color:#93c5fd;font-size:11px;font-weight:700;padding:3px 10px;
        border-radius:20px;white-space:nowrap;letter-spacing:0.04em;
        animation:tc-label-pulse 3s ease-in-out infinite;
      }
      @keyframes tc-label-pulse {
        0%,100%{opacity:.7;transform:translateY(0)}
        50%{opacity:1;transform:translateY(-2px)}
      }
      .tc-fab-ring { position:relative;display:flex;align-items:center;justify-content:center; }
      .tc-spark {
        position:absolute;font-size:13px;line-height:1;pointer-events:none;
        animation:tc-spark-anim 2.4s ease-in-out infinite;
      }
      .tc-spark:nth-child(1){top:-16px;left:50%;transform:translateX(-50%);animation-delay:0s;}
      .tc-spark:nth-child(2){top:16px;left:-18px;animation-delay:.9s;}
      .tc-spark:nth-child(3){top:16px;right:-18px;animation-delay:1.8s;}
      @keyframes tc-spark-anim {
        0%,100%{opacity:.3;transform:scale(.8) translateY(0);}
        50%{opacity:1;transform:scale(1.2) translateY(-4px);}
      }
      .tc-fab {
        width:58px;height:58px;border-radius:18px;
        background:linear-gradient(145deg,#1e3a8a,#1d4ed8,#3b82f6);
        border:none;cursor:pointer;
        box-shadow:0 4px 20px rgba(29,78,216,.6);
        display:flex;align-items:center;justify-content:center;
        transition:transform .15s,box-shadow .15s;
        /* Ligger OVANFOR halon och ringarna nedan. Utan egen z-index malas de
           positionerade lagren over knappen och taget bleks bort. */
        position:relative;z-index:1;
      }
      .tc-fab:hover{transform:scale(1.08);box-shadow:0 6px 28px rgba(29,78,216,.8);}

      /* ── Tagassistenten vaknar nar appen syns ──────────────────────────────
         Knappen sags inte av den som inte redan visste att den fanns. Sekvensen spelas
         EN gang, nar sokformularet ar i vy (se tcVackAssistenten), och halon andas sedan
         vidare tills chatten oppnats. Halon och ringarna ligger SIST i .tc-fab-ring med
         flit: gnistorna adresseras med :nth-child(1..3) och hade tappat sina platser annars. */
      .tc-halo {
        position:absolute;inset:-12px;border-radius:50%;pointer-events:none;z-index:0;
        background:radial-gradient(circle,rgba(59,130,246,.55) 0%,rgba(52,211,153,.18) 52%,transparent 72%);
        opacity:0;transition:opacity .4s ease;
      }
      .tc-fab-wrap.tc-lockar .tc-halo{opacity:1;animation:tc-halo-andas 3.2s ease-in-out infinite;}
      @keyframes tc-halo-andas {
        0%,100%{transform:scale(.9);opacity:.5;}
        50%{transform:scale(1.14);opacity:1;}
      }
      .tc-wave {
        position:absolute;inset:0;border-radius:18px;pointer-events:none;z-index:0;
        border:2px solid rgba(147,197,253,.75);opacity:0;
      }
      .tc-fab-wrap.tc-vaknar .tc-wave{animation:tc-wave 1.5s cubic-bezier(.2,.7,.3,1);}
      .tc-fab-wrap.tc-vaknar .tc-wave:nth-of-type(2){animation-delay:.38s;border-color:rgba(52,211,153,.6);}
      .tc-fab-wrap.tc-vaknar .tc-wave:nth-of-type(3){animation-delay:.76s;border-color:rgba(251,146,60,.55);}
      @keyframes tc-wave {
        0%{transform:scale(.72);opacity:.95;}
        65%{opacity:.25;}
        100%{transform:scale(2.5);opacity:0;}
      }
      /* Taget rullar in fran hoger och bromsar in. */
      .tc-fab-wrap.tc-vaknar .tc-fab{animation:tc-tag-rullar-in 1.15s cubic-bezier(.22,1,.36,1);}
      @keyframes tc-tag-rullar-in {
        0%{transform:translateX(30px) scale(.7);}
        45%{transform:translateX(-6px) scale(1.15);}
        70%{transform:translateX(3px) scale(.96);}
        100%{transform:none;}
      }
      /* Stralkastaren blinkar till nar taget stannat. */
      .tc-fab-wrap.tc-vaknar .tc-lampa{animation:tc-lampa-blink 1.2s ease-out;}
      @keyframes tc-lampa-blink {
        0%{fill:#93c5fd;}
        25%{fill:#fffbe6;}
        45%{fill:#fef08a;}
        65%{fill:#fffbe6;}
        100%{fill:#fef08a;}
      }
      /* Pratbubblan. Pekhandelser slacks med flit: den ska aldrig sta i vagen for knappen
         den pekar pa. width:max-content KRAVS — bubblan ar absolut placerad i .tc-fab-wrap,
         som ar lika smal som knappen, och utan egen bredd krymper den till en bokstav per rad. */
      .tc-chat-hej {
        position:absolute;right:72px;bottom:4px;z-index:1;
        width:max-content;max-width:min(240px,calc(100vw - 120px));
        background:linear-gradient(145deg,rgba(17,40,110,.96),rgba(29,78,216,.92));
        border:1px solid rgba(147,197,253,.4);border-radius:15px 15px 4px 15px;
        color:#eaf2ff;font-size:12px;font-weight:600;line-height:1.35;
        padding:9px 12px;box-shadow:0 10px 30px rgba(0,0,0,.5);
        pointer-events:none;opacity:0;transform:translateX(12px) scale(.9);
        transform-origin:100% 100%;transition:opacity .32s ease,transform .32s cubic-bezier(.22,1,.36,1);
      }
      .tc-fab-wrap.tc-hej .tc-chat-hej{opacity:1;transform:none;}
      @media (prefers-reduced-motion:reduce){
        .tc-halo,.tc-wave,.tc-fab-wrap.tc-vaknar .tc-fab,.tc-fab-wrap.tc-vaknar .tc-lampa{
          animation:none!important;
        }
        .tc-fab-wrap.tc-lockar .tc-halo{opacity:.75;}
      }
      .tc-panel {
        position:fixed;bottom:96px;right:24px;z-index:9998;
        width:380px;
        max-height:min(540px, calc(100vh - 130px));
        max-height:min(540px, calc(100dvh - 130px));
        /* Frostat glas som bilradgivningens chatt: basfargen ar nastan borta, det som gor
           panelen lasbar ar blur/saturate plus att varje textbarande del har en egen tatare
           bricka (ribba, bubblor, snabbknappar, inputrad). */
        background:
          linear-gradient(135deg,rgba(255,255,255,0.10),rgba(255,255,255,0) 46%),
          radial-gradient(120% 60% at 100% 0%,rgba(59,130,246,0.24),transparent 62%),
          radial-gradient(110% 55% at 0% 100%,rgba(13,110,132,0.26),transparent 68%),
          linear-gradient(160deg,rgba(10,20,48,0.30),rgba(6,12,30,0.38));
        backdrop-filter:blur(34px) saturate(180%);-webkit-backdrop-filter:blur(34px) saturate(180%);
        border:1px solid rgba(147,197,253,0.28);border-radius:22px;
        box-shadow:0 24px 64px rgba(0,0,0,.55),0 0 80px rgba(59,130,246,.30),
          0 0 130px rgba(45,212,191,.16),
          inset 0 1px 0 rgba(255,255,255,0.28),inset 0 0 0 1px rgba(255,255,255,0.06);
        display:none;flex-direction:column;overflow:hidden;
        font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
      }
      /* Fargspel bakom glaset: fyra mjuka falt som langsamt driver runt i tagappens palett.
         Bara transform animeras (komposit) — animerad background/filter pa ett element med
         backdrop-filter tvingar om-filtrering varje bildruta och hackar. */
      .tc-panel::before {
        content:"";position:absolute;inset:-45%;z-index:0;pointer-events:none;
        background:
          radial-gradient(42% 42% at 26% 28%,rgba(59,130,246,0.85),transparent 72%),
          radial-gradient(38% 38% at 76% 20%,rgba(34,211,238,0.62),transparent 72%),
          radial-gradient(44% 44% at 64% 80%,rgba(99,102,241,0.66),transparent 72%),
          radial-gradient(40% 40% at 18% 76%,rgba(45,212,191,0.52),transparent 72%);
        opacity:.62;
        animation:tc-aurora 24s ease-in-out infinite alternate;
      }
      @keyframes tc-aurora {
        0%   { transform:translate3d(-14%,-10%,0) rotate(0deg)   scale(1.10); }
        50%  { transform:translate3d(13%,9%,0)    rotate(16deg)  scale(1.34); }
        100% { transform:translate3d(-9%,14%,0)   rotate(-13deg) scale(1.16); }
      }
      /* Fargen som vandrar runt kanten (gradient-border-tricket med mask-composite).
         Utan @property star ringen still — da blir den bara en statisk fargkant. */
      @property --tc-rim-ang { syntax:'<angle>'; initial-value:0deg; inherits:false; }
      .tc-panel::after {
        content:"";position:absolute;inset:0;z-index:0;pointer-events:none;
        border-radius:inherit;padding:2px;
        background:conic-gradient(from var(--tc-rim-ang),
          #60a5fa,#22d3ee,#34d399,#818cf8,#fbbf24,#60a5fa);
        -webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);
        mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);
        -webkit-mask-composite:xor;mask-composite:exclude;
        opacity:.85;
        animation:tc-rim 9s linear infinite;
      }
      @keyframes tc-rim { to { --tc-rim-ang:360deg; } }
      /* Innehallet over fargspelet — annars malas texten under pseudoelementen */
      .tc-panel > * { position:relative;z-index:1; }
      @media (prefers-reduced-motion:reduce){
        .tc-panel::before,.tc-panel::after{ animation:none; }
      }
      /* Oppet/stangt bor i en klass pa body, inte i panelens inline style.
         Da kan FAB-regeln nedan uttrycka "expanderad OCH oppen" i ren CSS
         i stallet for att JS ska spegla tillstandet at den. */
      body.tc-chat-open .tc-panel{display:flex;}
      /* Ribban skiftar farg som bilradgivningens och elbilsappens — samma mekanism i alla tre,
         men i tagappens egen palett (djupblatt -> blatt -> indigo -> teal). Bandet ar dubbelt
         sa brett som rutan och panoreras: fargen vandrar, layouten star still. ALLA toner ar
         morka med flit — rubriken ar vit, och en ljus ton hade atit lasbarheten. */
      .tc-header {
        background:linear-gradient(110deg,
          rgba(12,26,58,0.94) 0%,rgba(23,49,110,0.92) 22%,rgba(37,99,235,0.86) 44%,
          rgba(67,56,202,0.84) 64%,rgba(13,110,132,0.86) 82%,rgba(23,49,110,0.92) 100%);
        background-size:220% 100%;
        animation:tc-header-skift 16s ease-in-out infinite alternate;
        backdrop-filter:blur(10px) saturate(150%);-webkit-backdrop-filter:blur(10px) saturate(150%);
        border-bottom:1px solid rgba(125,211,252,0.25);
        box-shadow:inset 0 1px 0 rgba(255,255,255,0.16);
        color:#fff;padding:13px 16px;
        display:flex;align-items:center;justify-content:space-between;
        font-weight:700;font-size:14px;flex-shrink:0;gap:8px;
      }
      @keyframes tc-header-skift {
        0%{background-position:0% 50%;}
        100%{background-position:100% 50%;}
      }
      @media (prefers-reduced-motion:reduce){
        .tc-header{animation:none;background-position:50% 50%;}
      }
      .tc-header-actions{display:flex;align-items:center;gap:6px;}
      .tc-header-clear {
        background:rgba(255,255,255,0.08);border:1px solid rgba(255,255,255,0.18);
        color:rgba(255,255,255,0.75);font-size:11px;font-weight:600;padding:3px 9px;
        border-radius:20px;cursor:pointer;transition:all .15s;white-space:nowrap;
      }
      .tc-header-clear:hover{background:rgba(255,255,255,0.16);color:#fff;}
      .tc-header-close {
        background:none;border:none;color:rgba(255,255,255,0.7);font-size:20px;
        cursor:pointer;padding:0 2px;line-height:1;transition:color .12s;
      }
      .tc-header-close:hover{color:#fff;}
      .tc-header-expand {
        background:none;border:none;color:rgba(255,255,255,0.7);
        cursor:pointer;padding:0 2px;line-height:0;transition:color .12s;
        display:flex;align-items:center;
      }
      .tc-header-expand:hover{color:#fff;}
      .tc-header-expand svg{display:block;transition:transform .18s;}
      body.tc-chat-max .tc-header-expand svg{transform:rotate(180deg);}
      .tc-context-bar {
        padding:7px 14px;font-size:11px;font-weight:600;
        color:rgba(191,219,254,0.88);letter-spacing:0.02em;
        background:rgba(8,18,44,0.46);border-bottom:1px solid rgba(147,197,253,0.14);
        backdrop-filter:blur(10px) saturate(140%);-webkit-backdrop-filter:blur(10px) saturate(140%);
        white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex-shrink:0;
      }
      .tc-messages {
        flex:1;overflow-y:auto;padding:14px 12px;
        display:flex;flex-direction:column;gap:10px;
        background:transparent;min-height:0;
      }
      .tc-messages::-webkit-scrollbar{width:4px;}
      .tc-messages::-webkit-scrollbar-track{background:transparent;}
      .tc-messages::-webkit-scrollbar-thumb{background:rgba(96,165,250,0.25);border-radius:4px;}
      .tc-bubble {
        max-width:85%;padding:10px 13px;border-radius:14px;
        font-size:13px;line-height:1.6;word-break:break-word;
      }
      /* Glasbricka med ett ljusstrak i overkanten, som bilradgivningens bubblor. Den gamla
         blaa glorian runt varje bubbla (0 0 28px) gjorde att de flot ihop med varandra. */
      .tc-bubble.bot {
        background:linear-gradient(150deg,rgba(255,255,255,0.08),rgba(255,255,255,0) 55%),
          rgba(12,24,58,0.58);
        backdrop-filter:blur(14px) saturate(150%);-webkit-backdrop-filter:blur(14px) saturate(150%);
        border:1px solid rgba(147,197,253,0.22);
        border-radius:4px 14px 14px 14px;align-self:flex-start;color:#e6f0ff;
        box-shadow:0 4px 18px -8px rgba(0,0,0,0.5),inset 0 1px 0 rgba(255,255,255,0.12);
      }
      .tc-bubble.bot strong{color:#bfdbfe;}
      .tc-bubble.bot ul{margin:6px 0 2px 16px;display:flex;flex-direction:column;gap:3px;}
      .tc-bubble.bot li{list-style:disc;}
      .tc-bubble.user {
        background:linear-gradient(135deg,rgba(29,78,216,0.76),rgba(59,130,246,0.66));
        backdrop-filter:blur(14px) saturate(160%);-webkit-backdrop-filter:blur(14px) saturate(160%);
        border:1px solid rgba(191,219,254,0.32);
        color:#fff;border-radius:14px 14px 4px 14px;align-self:flex-end;
        box-shadow:0 4px 18px -8px rgba(29,78,216,0.9),inset 0 1px 0 rgba(255,255,255,0.20);
      }
      .tc-quick {
        padding:10px 12px 4px;display:flex;flex-wrap:wrap;gap:7px;flex-shrink:0;
        background:rgba(8,18,44,0.42);border-top:1px solid rgba(147,197,253,0.16);
        backdrop-filter:blur(10px) saturate(140%);-webkit-backdrop-filter:blur(10px) saturate(140%);
      }
      /* JS-styrt lage: satts nar samtalet borjat sa att snabbknapparna forsvinner.
         Egen klass i stallet for inline display, annars kan CSS-reglerna nedan
         (platsbrist i liggande lage, expanderat lage) aldrig ta over. */
      .tc-quick.tc-quick-off{display:none;}
      .tc-quick-btn {
        --ton:96,165,250;
        display:inline-flex;align-items:center;gap:6px;
        background:linear-gradient(145deg,rgba(var(--ton),0.20),rgba(var(--ton),0.09));
        border:1px solid rgba(var(--ton),0.42);color:#eef5ff;
        border-radius:20px;padding:6px 13px 6px 7px;font-size:12px;font-weight:600;
        cursor:pointer;white-space:nowrap;letter-spacing:.1px;
        box-shadow:0 2px 10px -6px rgba(var(--ton),0.9);
        transition:transform .15s ease,box-shadow .15s ease,border-color .15s ease,background .15s ease;
        backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);
      }
      /* Ikonen i en egen bricka - annars drunknar den i texten bredvid. */
      .tc-quick-ik {
        display:inline-flex;align-items:center;justify-content:center;
        width:20px;height:20px;border-radius:50%;flex-shrink:0;font-size:11px;
        background:rgba(var(--ton),0.26);
      }
      .tc-quick-btn:hover{
        background:linear-gradient(145deg,rgba(var(--ton),0.34),rgba(var(--ton),0.16));
        border-color:rgba(var(--ton),0.75);color:#fff;
        transform:translateY(-1px);box-shadow:0 6px 16px -7px rgba(var(--ton),1);
      }
      .tc-quick-btn:active{transform:translateY(0);}
      .tc-quick-btn:focus-visible{outline:2px solid rgba(var(--ton),0.9);outline-offset:2px;}
      @media(prefers-reduced-motion:reduce){.tc-quick-btn{transition:none;}.tc-quick-btn:hover{transform:none;}}
      .tc-input-row {
        display:flex;gap:8px;padding:10px 12px;
        border-top:1px solid rgba(147,197,253,0.16);
        background:rgba(8,18,44,0.42);flex-shrink:0;
        backdrop-filter:blur(10px) saturate(140%);-webkit-backdrop-filter:blur(10px) saturate(140%);
      }
      .tc-input {
        flex:1;border:1px solid rgba(147,197,253,0.26);border-radius:22px;
        padding:8px 14px;font-size:13px;outline:none;
        background:rgba(12,24,58,0.42);color:#e6f0ff;transition:border-color .15s,box-shadow .15s;
        box-shadow:inset 0 1px 0 rgba(255,255,255,0.10);
        backdrop-filter:blur(10px) saturate(140%);-webkit-backdrop-filter:blur(10px) saturate(140%);
      }
      .tc-input::placeholder{color:rgba(147,197,253,0.35);}
      .tc-input:focus{border-color:rgba(147,197,253,0.5);box-shadow:0 0 0 3px rgba(59,130,246,0.12);}
      .tc-send {
        width:38px;height:38px;border-radius:50%;
        background:linear-gradient(135deg,#1d4ed8,#3b82f6);
        color:#fff;border:none;cursor:pointer;
        font-size:16px;display:flex;align-items:center;justify-content:center;
        flex-shrink:0;transition:all .15s;
        box-shadow:0 2px 10px rgba(59,130,246,0.35);
      }
      .tc-send:hover{background:linear-gradient(135deg,#2563eb,#60a5fa);box-shadow:0 4px 14px rgba(59,130,246,0.5);}
      .tc-typing{display:flex;gap:4px;align-items:center;padding:4px 0;}
      .tc-typing span{
        width:7px;height:7px;border-radius:50%;background:rgba(147,197,253,0.5);
        animation:tc-bounce .9s infinite;display:inline-block;
      }
      .tc-typing span:nth-child(2){animation-delay:.15s;}
      .tc-typing span:nth-child(3){animation-delay:.3s;}
      @keyframes tc-bounce{0%,80%,100%{transform:translateY(0)}40%{transform:translateY(-6px)}}
      .tc-cursor{display:inline-block;width:2px;height:13px;background:#93c5fd;margin-left:2px;border-radius:1px;animation:tc-cursor-blink .55s steps(1) infinite;vertical-align:middle;}
      @keyframes tc-cursor-blink{0%,100%{opacity:1}50%{opacity:0}}
      .tc-feedback{display:flex;gap:6px;margin-top:6px;padding-left:2px;align-items:center;}
      .tc-thumb{background:none;border:1px solid rgba(96,165,250,0.18);color:rgba(147,197,253,0.38);font-size:12px;padding:2px 8px;border-radius:10px;cursor:pointer;transition:all .15s;line-height:1.5;}
      .tc-thumb:hover{border-color:rgba(96,165,250,0.5);color:#93c5fd;}
      .tc-thumb.voted{border-color:rgba(96,165,250,0.65);color:#93c5fd;background:rgba(96,165,250,0.08);}
      .tc-retry{background:none;border:1px solid rgba(239,68,68,0.3);color:rgba(239,68,68,0.65);font-size:11px;font-weight:600;padding:4px 11px;border-radius:20px;cursor:pointer;margin-top:7px;display:inline-block;transition:all .15s;}
      .tc-retry:hover{border-color:rgba(239,68,68,0.6);color:#ef4444;}
      /* Vagnsskiss under svaret när frågan gäller toalett/bistro/närmaste plats */
      .tc-sketch{margin-top:9px;padding:9px 10px;border-radius:12px;
        background:rgba(96,165,250,0.08);border:1px solid rgba(96,165,250,0.22);}
      .tc-sketch-title{font-size:11px;font-weight:800;letter-spacing:.02em;color:#bfdbfe;margin-bottom:7px;}
      .tc-sketch-row{display:flex;align-items:stretch;gap:4px;overflow-x:auto;padding-bottom:2px;}
      .tc-sk-wagon{flex:1 0 78px;display:flex;flex-direction:column;gap:1px;text-align:center;
        padding:6px 5px;border-radius:9px;background:rgba(255,255,255,0.05);
        border:1px solid rgba(255,255,255,0.14);font-size:9.5px;color:rgba(255,255,255,.55);line-height:1.3;}
      .tc-sk-wagon b{font-size:10.5px;color:rgba(255,255,255,.9);}
      .tc-sk-wagon.mine{background:rgba(52,211,153,0.14);border-color:rgba(52,211,153,0.5);}
      .tc-sk-rows{opacity:.65;}
      .tc-sk-icons{font-size:12px;letter-spacing:1px;margin-top:2px;}
      .tc-sk-you{margin-top:2px;font-weight:800;color:#6ee7b7;font-size:9px;}
      .tc-sk-bistro{flex:0 0 auto;align-self:center;padding:5px 7px;border-radius:9px;white-space:nowrap;
        background:rgba(251,191,36,0.16);border:1px solid rgba(251,191,36,0.45);
        color:#fde68a;font-size:9.5px;font-weight:800;}
      .tc-sketch-note{margin-top:6px;font-size:9.5px;color:rgba(255,255,255,.35);line-height:1.4;}
      .tc-seat-picks{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-top:8px;}
      .tc-seat-picks-lbl{font-size:10px;font-weight:800;color:rgba(147,197,253,.75);letter-spacing:.03em;}
      .tc-seat-pick{background:rgba(52,211,153,.16);border:1px solid rgba(52,211,153,.5);color:#6ee7b7;font-size:11px;font-weight:700;padding:4px 10px;border-radius:20px;cursor:pointer;transition:all .15s;}
      .tc-seat-pick:hover{background:rgba(52,211,153,.32);color:#ecfdf5;}
      .tc-sketch-src{opacity:.8;}
      .tc-train-imgs{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px;}
      .tc-train-img{width:100%;max-height:130px;object-fit:cover;border-radius:10px;opacity:.88;transition:opacity .2s;}
      .tc-train-img:hover{opacity:1;}
      /* Resekartan i chatten */
      .tc-routemap{margin-top:8px;max-width:92%;border-radius:12px;overflow:hidden;
        border:1px solid rgba(147,197,253,0.3);background:rgba(8,18,44,0.6);
        box-shadow:0 6px 20px -8px rgba(0,0,0,0.6);}
      .tc-routemap-karta{height:170px;background:#1b1d22;}
      .tc-routemap-cap{padding:6px 10px;font-size:11px;font-weight:700;color:#dbeafe;
        border-top:1px solid rgba(147,197,253,0.18);}
      .tc-routemap .leaflet-control-attribution{font-size:8px;}
      .leaflet-tooltip.tc-rm-tip{background:rgba(8,18,44,0.88);color:#fff;border:1px solid rgba(147,197,253,0.4);
        border-radius:6px;font-size:10.5px;font-weight:700;padding:1px 6px;box-shadow:none;}
      .leaflet-tooltip.tc-rm-tip::before{display:none;}
      .tc-followup-chips{display:flex;flex-wrap:wrap;gap:5px;margin-top:7px;}
      .tc-followup-chip{background:linear-gradient(145deg,rgba(96,165,250,.16),rgba(96,165,250,.06));border:1px solid rgba(147,197,253,.32);color:#dbeafe;font-size:11px;font-weight:600;padding:4px 11px;border-radius:20px;cursor:pointer;transition:all .15s;box-shadow:inset 0 1px 0 rgba(255,255,255,.08);}
      .tc-followup-chip:hover{background:linear-gradient(145deg,rgba(96,165,250,.32),rgba(96,165,250,.14));border-color:rgba(147,197,253,.6);color:#fff;transform:translateY(-1px);}
      @keyframes tc-dep-flash{0%,100%{box-shadow:none}30%,70%{box-shadow:0 0 0 3px rgba(96,165,250,.6),0 0 20px rgba(96,165,250,.2)}}
      .tc-dep-highlight{animation:tc-dep-flash 2s ease;}
      @keyframes tc-chip-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
      .tc-search-chip {
        position:fixed;bottom:100px;right:24px;z-index:9997;
        background:linear-gradient(135deg,rgba(29,78,216,0.92),rgba(59,130,246,0.88));
        backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);
        border:1px solid rgba(147,197,253,0.35);border-radius:22px;
        padding:8px 14px 8px 10px;display:flex;align-items:center;gap:8px;cursor:pointer;
        box-shadow:0 4px 20px rgba(0,0,0,0.5);
        font-family:-apple-system,BlinkMacSystemFont,sans-serif;
        font-size:12px;font-weight:600;color:#dbeafe;
        animation:tc-chip-in .3s ease-out;
      }
      .tc-search-chip-icon{font-size:16px;}
      .tc-search-chip-sub{font-weight:400;font-size:11px;opacity:.8;}
      @media(max-width:640px){
        /* Panelen ska vara ett kort i underkanten — inte ta över hela skarmen */
        .tc-panel{
          width:auto;left:10px;right:10px;bottom:88px;border-radius:16px;
          max-height:min(440px, 58vh);
          max-height:min(440px, 58dvh);
        }
        .tc-fab-wrap{right:12px;bottom:12px;gap:4px;}
        /* Bredvid knappen finns ingen plats kvar pa en telefon — halsningen far lagga sig
           ovanfor i stallet, med hela skarmbredden minus marginalerna. */
        .tc-chat-hej{right:0;bottom:62px;max-width:calc(100vw - 36px);white-space:normal;
          border-radius:15px 15px 15px 4px;transform:translateY(10px) scale(.92);transform-origin:100% 0;}
        .tc-wave{border-radius:15px;}
        .tc-fab{width:48px;height:48px;border-radius:15px;}
        .tc-fab svg{width:40px;height:22px;}
        .tc-fab-label{font-size:10px;padding:2px 8px;}
        .tc-spark{font-size:11px;}
        .tc-spark:nth-child(2){top:14px;left:-14px;}
        .tc-spark:nth-child(3){top:14px;right:-14px;}
        .tc-header{padding:10px 12px;font-size:13px;}
        .tc-context-bar{padding:6px 12px;}
        .tc-messages{padding:11px 10px;gap:8px;}
        .tc-bubble{max-width:90%;padding:9px 11px;}
        .tc-quick{padding:8px 10px 3px;gap:6px;}
        .tc-quick-btn{font-size:11px;padding:4px 10px 4px 5px;}
        .tc-quick-ik{width:18px;height:18px;font-size:10px;}
        .tc-input-row{padding:8px 10px;}
        .tc-train-img{max-height:100px;}
        .tc-search-chip{right:10px;left:10px;bottom:70px;}
      }
      /* Liggande mobil: nastan ingen hojd kvar — hall panelen riktigt lag */
      @media(max-width:900px) and (max-height:480px){
        .tc-panel{bottom:72px;max-height:min(300px, 70vh);max-height:min(300px, 70dvh);}
        .tc-quick{display:none;}
        .tc-fab-label{display:none;}
        .tc-fab{width:44px;height:44px;}
        .tc-fab svg{width:36px;height:20px;}
      }
      /* Expanderat lage. Klassen sitter pa body sa att aven .tc-fab-wrap gar att na,
         och sa att specificiteten (0,2,1) slar bade bas- och mediaregler ovan. */
      body.tc-chat-max .tc-panel{
        width:min(560px, calc(100vw - 48px));
        max-height:calc(100vh - 120px);
        max-height:calc(100dvh - 120px);
      }
      /* Smal eller lag skarm: expanderat = helskarmsark, FAB:en i vagen doljs */
      @media(max-width:640px),(max-height:480px){
        body.tc-chat-max .tc-panel{
          left:8px;right:8px;top:8px;bottom:8px;
          width:auto;max-height:none;border-radius:14px;
        }
        body.tc-chat-open.tc-chat-max .tc-fab-wrap{display:none;}
        body.tc-chat-max .tc-quick:not(.tc-quick-off){display:flex;}
      }
    `;
    document.head.appendChild(style);

    var root = document.createElement("div");
    root.innerHTML = `
      <div class="tc-fab-wrap">
        <span class="tc-fab-label">🚂 Fråga AI</span>
        <div class="tc-fab-ring">
          <span class="tc-spark">🎫</span>
          <span class="tc-spark">⚡</span>
          <span class="tc-spark">🎫</span>
          <button class="tc-fab" id="tc-fab" title="Fråga tågassistenten">
            <svg viewBox="0 0 88 42" width="48" height="26" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="g-loco" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#60a5fa"/><stop offset="100%" stop-color="#1d4ed8"/></linearGradient>
                <linearGradient id="g-w1"   x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#fb923c"/><stop offset="100%" stop-color="#c2410c"/></linearGradient>
                <linearGradient id="g-w2"   x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#34d399"/><stop offset="100%" stop-color="#047857"/></linearGradient>
              </defs>
              <!-- vagn 2 (teal) -->
              <rect x="2" y="7" width="26" height="19" rx="3" fill="url(#g-w2)" stroke="rgba(52,211,153,0.6)" stroke-width="0.8"/>
              <rect x="6"  y="11" width="7" height="5" rx="1.5" fill="rgba(255,255,255,0.3)"/>
              <rect x="17" y="11" width="7" height="5" rx="1.5" fill="rgba(255,255,255,0.3)"/>
              <line x1="2" y1="21" x2="28" y2="21" stroke="rgba(255,255,255,0.25)" stroke-width="1.5"/>
              <!-- koppling -->
              <rect x="28" y="15" width="4" height="3" rx="1" fill="rgba(200,220,255,0.35)"/>
              <!-- vagn 1 (orange) -->
              <rect x="32" y="7" width="24" height="19" rx="2" fill="url(#g-w1)" stroke="rgba(251,146,60,0.6)" stroke-width="0.8"/>
              <rect x="36" y="11" width="7" height="5" rx="1.5" fill="rgba(255,255,255,0.3)"/>
              <rect x="46" y="11" width="7" height="5" rx="1.5" fill="rgba(255,255,255,0.3)"/>
              <line x1="32" y1="21" x2="56" y2="21" stroke="rgba(255,255,255,0.25)" stroke-width="1.5"/>
              <!-- koppling -->
              <rect x="56" y="15" width="4" height="3" rx="1" fill="rgba(200,220,255,0.35)"/>
              <!-- lokomotiv (blå, spetsig nos) -->
              <path d="M 60 7 L 82 7 L 88 16.5 L 82 26 L 60 26 Z" fill="url(#g-loco)" stroke="rgba(147,197,253,0.55)" stroke-width="0.8"/>
              <!-- förarhyttsfönster -->
              <path d="M 82 11 L 87 16.5 L 82 22" fill="rgba(186,230,253,0.4)" stroke="rgba(186,230,253,0.5)" stroke-width="0.6"/>
              <!-- sidofönster loko -->
              <rect x="64" y="11" width="7" height="5" rx="1.5" fill="rgba(255,255,255,0.3)"/>
              <rect x="74" y="11" width="6" height="5" rx="1.5" fill="rgba(255,255,255,0.3)"/>
              <!-- strålkastare -->
              <ellipse class="tc-lampa" cx="87.5" cy="16.5" rx="1.8" ry="1.3" fill="#fef08a"/>
              <ellipse cx="87.5" cy="16.5" rx="0.8" ry="0.6" fill="#fff"/>
              <line x1="60" y1="21" x2="82" y2="21" stroke="rgba(255,255,255,0.25)" stroke-width="1.5"/>
              <!-- hjul (färgade per vagn) -->
              <circle cx="10"  cy="30" r="3.5" fill="#082f1f" stroke="#34d399" stroke-width="1.3"/><circle cx="10"  cy="30" r="1.4" fill="rgba(52,211,153,0.5)"/>
              <circle cx="22"  cy="30" r="3.5" fill="#082f1f" stroke="#34d399" stroke-width="1.3"/><circle cx="22"  cy="30" r="1.4" fill="rgba(52,211,153,0.5)"/>
              <circle cx="40"  cy="30" r="3.5" fill="#1c0a00" stroke="#fb923c" stroke-width="1.3"/><circle cx="40"  cy="30" r="1.4" fill="rgba(251,146,60,0.5)"/>
              <circle cx="52"  cy="30" r="3.5" fill="#1c0a00" stroke="#fb923c" stroke-width="1.3"/><circle cx="52"  cy="30" r="1.4" fill="rgba(251,146,60,0.5)"/>
              <circle cx="68"  cy="30" r="3.5" fill="#060d1f" stroke="#60a5fa" stroke-width="1.3"/><circle cx="68"  cy="30" r="1.4" fill="rgba(96,165,250,0.5)"/>
              <circle cx="80"  cy="30" r="3.5" fill="#060d1f" stroke="#60a5fa" stroke-width="1.3"/><circle cx="80"  cy="30" r="1.4" fill="rgba(96,165,250,0.5)"/>
              <!-- sliprar -->
              <rect x="0"  y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="7"  y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="14" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="21" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="28" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="35" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="42" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="49" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="56" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="63" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="70" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="77" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <rect x="84" y="33.5" width="4" height="5" rx="0.7" fill="rgba(100,140,200,0.22)"/>
              <!-- räls -->
              <line x1="0" y1="33.5" x2="88" y2="33.5" stroke="rgba(147,197,253,0.7)" stroke-width="1.8" stroke-linecap="round"/>
              <line x1="0" y1="37.5" x2="88" y2="37.5" stroke="rgba(147,197,253,0.7)" stroke-width="1.8" stroke-linecap="round"/>
            </svg>
          </button>
          <span class="tc-halo"></span>
          <span class="tc-wave"></span>
          <span class="tc-wave"></span>
          <span class="tc-wave"></span>
        </div>
        <div class="tc-chat-hej">👋 Hej! Fråga mig om avgångar, platser och priser.</div>
      </div>
      <div class="tc-panel" id="tc-panel">
        <div class="tc-header">
          <span>🚂 Tågassistenten</span>
          <div class="tc-header-actions">
            <button class="tc-header-clear" id="tc-clear">Rensa</button>
            <button class="tc-header-expand" id="tc-expand" title="Expandera chatten" aria-label="Expandera chatten" aria-expanded="false">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 15l6-6 6 6"/></svg>
            </button>
            <button class="tc-header-close" id="tc-close">✕</button>
          </div>
        </div>
        <div class="tc-context-bar" id="tc-context-bar" style="display:none;"></div>
        <div class="tc-messages" id="tc-messages"></div>
        <div class="tc-quick" id="tc-quick">${tcStartChips()}</div>
        <div class="tc-input-row">
          <input class="tc-input" id="tc-input" type="text" placeholder="Fråga om avgångar, priser, platser…" autocomplete="off"/>
          <button class="tc-send" id="tc-send">➤</button>
        </div>
      </div>
    `;
    document.body.appendChild(root);

    tcAppendBot("Hej! Jag hjälper dig hitta rätt tåg 🚂 Gör en sökning så kan jag svara på frågor om priser, platser och restider!", false);

    if (trainChatHistory.length > 0) {
      document.getElementById("tc-quick").classList.add("tc-quick-off");
      trainChatHistory.forEach(function(m) {
        if (m.role === "user") tcAppendUser(m.content);
        else if (m.role === "assistant") tcAppendBot(m.content, false);
      });
    }

    document.getElementById("tc-fab").addEventListener("click", tcToggle);
    document.getElementById("tc-close").addEventListener("click", tcToggle);
    document.getElementById("tc-expand").addEventListener("click", tcToggleExpanded);
    document.getElementById("tc-send").addEventListener("click", tcSend);
    document.getElementById("tc-clear").addEventListener("click", tcClear);
    document.getElementById("tc-input").addEventListener("keydown", function(e) { if (e.key === "Enter") tcSend(); });
    document.getElementById("tc-quick").addEventListener("click", function(e) {
      var btn = e.target.closest(".tc-quick-btn");
      if (btn) tcSendMessage(btn.dataset.q);
    });

    document.body.classList.toggle("tc-chat-max", tcExpanded);
    tcUpdateExpandBtn();
  }

  function tcIsOpen() {
    return document.body.classList.contains("tc-chat-open");
  }

  function tcSetOpen(open) {
    document.body.classList.toggle("tc-chat-open", open);
    if (open) {
      // Lockropet har gjort sitt i samma stund chatten oppnas — en halo som fortsatter
      // pulsa bakom en oppen panel ar bara brus.
      tcVackt = true;
      tcLugnaAssistenten();
      updateContextBar();
      document.getElementById("tc-input").focus();
      tcKartorOmrakna();
    }
  }

  // ── Assistenten vaknar nar besokaren ser appen ────────────────────────────────
  //
  // Knappen i hornet sags inte: den som inte redan visste att den fanns rullade forbi.
  // Uppvakningen ar darfor en sekvens — ringar som slar ut, taget som rullar in och
  // blinkar med stralkastaren, och en pratbubbla som sager vad den kan.
  //
  // Triggern ar att VALKOMSTSPLASHEN slappt, inte att sidan laddat. Appen ligger i ett
  // iframe pa elitrobban.se/minipristaget/ och rullas fram i foraldersidan just nar
  // splashen lyfter (mptRullaFramAppen i index.html) — det ar da besokaren faktiskt ser
  // appen. En uppvakning som spelas bakom splashlagret syns inte alls.
  var tcVackt = false;

  function tcLugnaAssistenten() {
    var wrap = document.querySelector(".tc-fab-wrap");
    if (wrap) wrap.classList.remove("tc-lockar", "tc-hej", "tc-vaknar");
  }

  function tcVackAssistenten() {
    if (tcVackt) return;
    var wrap = document.querySelector(".tc-fab-wrap");
    if (!wrap) return;
    if (tcIsOpen()) { tcVackt = true; return; }
    tcVackt = true;

    var lugnt = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    wrap.classList.add("tc-lockar");
    if (!lugnt) {
      wrap.classList.add("tc-vaknar");
      setTimeout(function () { wrap.classList.remove("tc-vaknar"); }, 1900);
    }
    setTimeout(function () { if (!tcIsOpen()) wrap.classList.add("tc-hej"); }, lugnt ? 0 : 700);
    setTimeout(function () { wrap.classList.remove("tc-hej"); }, 9000);
  }

  function tcVackNarSplashenSlappt() {
    var forsok = 0;
    (function vanta() {
      if (document.getElementById("mpt-intro")) {
        if (++forsok > 80) return; // ~20 s: splashen hanger kvar, da avstar vi hellre
        setTimeout(vanta, 250);
        return;
      }
      // Extra andrum: foraldersidan rullar fram ramen i samma ogonblick splashen lyfter,
      // och bubblan ska inte komma medan sidan fortfarande ror sig.
      setTimeout(tcVackAssistenten, 900);
    })();
  }

  function tcToggle() {
    tcSetOpen(!tcIsOpen());
  }

  function tcUpdateExpandBtn() {
    var btn = document.getElementById("tc-expand");
    if (!btn) return;
    var lbl = tcExpanded ? "Minska chatten" : "Expandera chatten";
    btn.title = lbl;
    btn.setAttribute("aria-label", lbl);
    btn.setAttribute("aria-expanded", tcExpanded ? "true" : "false");
  }

  function tcToggleExpanded() {
    tcExpanded = !tcExpanded;
    try { localStorage.setItem("tc-chat-max", tcExpanded ? "1" : "0"); } catch(e) {}
    document.body.classList.toggle("tc-chat-max", tcExpanded);
    tcUpdateExpandBtn();
    var msgs = document.getElementById("tc-messages");
    if (msgs) msgs.scrollTop = msgs.scrollHeight;
    tcKartorOmrakna();
  }

  function updateContextBar() {
    var bar = document.getElementById("tc-context-bar");
    if (!bar) return;
    if (_focusedDep) {
      var tr = _focusedDep.depTime + (_focusedDep.arrTime ? ' – ' + _focusedDep.arrTime : '');
      bar.textContent = "🚂 " + tr + " · " + _focusedDep.fromName + " → " + _focusedDep.toName;
      bar.style.display = "block";
      return;
    }
    var data = window._trainSearchData;
    if (data && data.fromName) {
      bar.textContent = "🔍 " + data.fromName + " → " + data.toName + "  |  📅 " + data.date + "  |  🚂 " + (data.departures ? data.departures.length : 0) + " avgångar";
      bar.style.display = "block";
    } else {
      bar.style.display = "none";
    }
  }

  var TC_TRAIN_IMAGES = [
    // SJ 3000 före x2000 i listan: "SJ 3000 (X55)" innehåller inte "x2000", men båda
    // nämns ofta i samma svar och då ska den specifika träffen komma först.
    { keys: ['sj 3000', 'sj3000', 'x55'],   src: '/images/train-sj-3000.jpg',     alt: 'SJ 3000 (X55) · Foto: SJ AB, CC BY 3.0' },
    { keys: ['x2000'],                      src: '/images/train-sj-x2000.jpg',    alt: 'SJ X2000' },
    { keys: ['x74', 'mtrx'],               src: '/images/train-vy.jpg',           alt: 'MTRX X74' },
    { keys: ['öresundståg', 'x31'],        src: '/images/train-oresundstag.jpg',  alt: 'Öresundståg X31' },
    { keys: ['snälltåget', 'snälltåg'],    src: '/images/train-sj-fast.png',     alt: 'Snälltåget' },
    { keys: ['mtr express'],               src: '/images/train-sj-fast.png',     alt: 'MTR Express' },
    { keys: ['x61', 'västtåg', 'västtrafik'], src: '/images/train-sj-regional.png', alt: 'Västtåg X61' },
  ];

  function tcInjectTrainImages(text, outer) {
    var lower = text.toLowerCase();
    var seen = {};
    var matches = [];
    TC_TRAIN_IMAGES.forEach(function(entry) {
      if (seen[entry.src]) return;
      for (var i = 0; i < entry.keys.length; i++) {
        if (lower.indexOf(entry.keys[i]) !== -1) {
          seen[entry.src] = true;
          matches.push(entry);
          break;
        }
      }
    });
    if (matches.length === 0) return;
    var wrap = document.createElement('div');
    wrap.className = 'tc-train-imgs';
    matches.forEach(function(entry) {
      var img = document.createElement('img');
      img.className = 'tc-train-img';
      img.src = entry.src;
      img.alt = entry.alt;
      img.title = entry.alt;
      wrap.appendChild(img);
    });
    outer.appendChild(wrap);
  }

  function tcAddFollowupChips(text, outer) {
    var lower = text.toLowerCase();
    var chips = [];
    if (/x2000/.test(lower))            chips.push('Vilka klasser finns på X2000?', 'Finns bistro ombord?');
    else if (/x74|mtrx/.test(lower))    chips.push('Vilka klasser finns på X74?', 'Hur snabbt är X74?');
    else if (/öresundståg/.test(lower)) chips.push('Vad kostar Öresundståget?', 'Öppen placering?');
    else if (/snälltåget/.test(lower))  chips.push('Finns liggvagn?', 'Vilka rutter trafikerar Snälltåget?');
    if (/wifi|5g/.test(lower) && chips.length < 2)    chips.push('Vad mer finns ombord?');
    if (/pris|kr|billig/.test(lower) && chips.length < 2) chips.push('Finns billigare alternativ?');
    if (/restid|timm|minut/.test(lower) && chips.length < 2) chips.push('Snabbaste avgången?');
    if (chips.length === 0) chips = ['Billigaste biljett?', 'Finns platser kvar?'];
    var wrap = document.createElement('div');
    wrap.className = 'tc-followup-chips';
    chips.slice(0, 3).forEach(function(chip) {
      var btn = document.createElement('button');
      btn.className = 'tc-followup-chip';
      btn.textContent = chip;
      btn.onclick = function() { wrap.remove(); document.getElementById('tc-input').value = chip; tcSend(); };
      wrap.appendChild(btn);
    });
    outer.appendChild(wrap);
  }

  function tcHighlightDepartures(text) {
    var times = text.match(/\b(\d{1,2}:\d{2})\b/g);
    if (!times) return;
    var seen = {};
    times.forEach(function(t) {
      if (seen[t]) return; seen[t] = true;
      document.querySelectorAll('.card-time-range').forEach(function(el) {
        if (el.textContent.indexOf(t) !== -1) {
          var card = el.closest('.dep-card');
          if (card) {
            card.classList.remove('tc-dep-highlight');
            void card.offsetWidth;
            card.classList.add('tc-dep-highlight');
            card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
          }
        }
      });
    });
  }

  function tcMarkdown(text) {
    return text
      .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")
      .replace(/\*\*(.+?)\*\*/g,"<strong>$1</strong>")
      .replace(/\*(.+?)\*/g,"<em>$1</em>")
      .replace(/^[-•]\s+(.+)$/gm,"<li>$1</li>")
      .replace(/(<li>[\s\S]*<\/li>)/,"<ul>$1</ul>")
      .replace(/\n/g,"<br>");
  }

  function tcAppendBot(text, animate) {
    var msgs = document.getElementById("tc-messages");
    var outer = document.createElement("div");
    var bubble = document.createElement("div");
    bubble.className = "tc-bubble bot";
    outer.appendChild(bubble);
    msgs.appendChild(outer);
    msgs.scrollTop = msgs.scrollHeight;
    if (animate !== false && text.length > 0) {
      var i = 0;
      var speed = Math.max(6, Math.min(20, 2600 / text.length));
      (function tick() {
        i += 3;
        if (i >= text.length) {
          bubble.innerHTML = tcMarkdown(text);
          tcInjectTrainImages(text, outer);
          tcAddFeedback(outer);
          msgs.scrollTop = msgs.scrollHeight;
        } else {
          bubble.textContent = text.slice(0, i);
          var cur = document.createElement("span"); cur.className = "tc-cursor";
          bubble.appendChild(cur);
          msgs.scrollTop = msgs.scrollHeight;
          setTimeout(tick, speed);
        }
      })();
    } else {
      bubble.innerHTML = tcMarkdown(text);
      tcInjectTrainImages(text, outer);
      if (animate !== false) tcAddFeedback(outer);
    }
    return outer;
  }

  // ── Resekartan i chatten ──────────────────────────────────────────────────────
  // Den valda resan ritad på en liten karta under chattens beskrivning av avgången:
  // start (grön), byte (gul) och mål (röd). Linjen följer spåret via mellanstationerna när
  // linjekartan känner sträckan, annars ett rakt streck. Koordinaterna hämtas från servern
  // (/api/station-coords), så kartan fungerar för alla stationer, inte bara linjekartans.
  var _tcKartor = [];

  function tcAppendRouteMap(outer, stopp) {
    stopp = stopp.filter(Boolean);
    if (!outer || stopp.length < 2 || !window.mptLaddaLeaflet) return;
    var box = document.createElement("div");
    box.className = "tc-routemap";
    box.innerHTML = '<div class="tc-routemap-karta"></div>' +
      '<div class="tc-routemap-cap">🗺 ' + stopp.map(tcEsc).join(' → ') + '</div>';
    outer.appendChild(box);
    var el = box.querySelector(".tc-routemap-karta");

    fetch('/api/station-coords?names=' + encodeURIComponent(stopp.join('|')))
      .then(function (r) { return r.json(); })
      .then(function (pts) {
        if (!pts || pts.length < 2) { box.remove(); return; }
        window.mptLaddaLeaflet(function () { tcRitaRuttkarta(el, pts); });
      })
      .catch(function () { box.remove(); });
  }

  function tcRitaRuttkarta(el, pts) {
    var karta = L.map(el, { zoomControl: false, scrollWheelZoom: false, attributionControl: true,
                            zoomSnap: 0.25, doubleClickZoom: false });
    if (window.mptKartbilder) window.mptKartbilder(karta);
    // Spåret: följ linjekartans mellanstationer per delsträcka när sådana finns
    var linje = [];
    for (var i = 0; i < pts.length - 1; i++) {
      var del = window.mptRuttPunkter && window.mptRuttPunkter(pts[i].name, pts[i + 1].name);
      var bit = del || [[pts[i].lat, pts[i].lon], [pts[i + 1].lat, pts[i + 1].lon]];
      linje = linje.concat(i === 0 ? bit : bit.slice(1));
    }
    L.polyline(linje, { color: '#60a5fa', weight: 9, opacity: .25, lineCap: 'round' }).addTo(karta);
    L.polyline(linje, { color: '#93c5fd', weight: 4, opacity: .95, lineCap: 'round' }).addTo(karta);
    pts.forEach(function (p, i) {
      var sist = i === pts.length - 1, forst = i === 0;
      L.circleMarker([p.lat, p.lon], {
        radius: forst || sist ? 7 : 6, color: '#fff', weight: 2, fillOpacity: 1,
        fillColor: forst ? '#34d399' : sist ? '#f87171' : '#fbbf24'
      }).bindTooltip(p.name, { permanent: true, direction: 'auto',
                               className: 'tc-rm-tip', offset: [0, 0] }).addTo(karta);
    });
    var granser = L.latLngBounds(linje);
    karta.fitBounds(granser, { paddingTopLeft: [70, 26], paddingBottomRight: [70, 26] });
    _tcKartor.push({ karta: karta, granser: granser });
  }

  // Kartan ritas ofta medan panelen är dold (mobil) — då är dess storlek 0. Räkna om när
  // panelen öppnas eller byter storlek.
  function tcKartorOmrakna() {
    setTimeout(function () {
      _tcKartor.forEach(function (k) {
        k.karta.invalidateSize();
        k.karta.fitBounds(k.granser, { paddingTopLeft: [70, 26], paddingBottomRight: [70, 26] });
      });
    }, 90);
  }

  function tcEsc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function tcAddFeedback(outer) {
    var fb = document.createElement("div"); fb.className = "tc-feedback";
    fb.innerHTML = '<button class="tc-thumb">👍</button><button class="tc-thumb">👎</button>';
    fb.querySelectorAll(".tc-thumb").forEach(function(btn) {
      btn.addEventListener("click", function() {
        fb.querySelectorAll(".tc-thumb").forEach(function(b) { b.classList.remove("voted"); });
        btn.classList.add("voted");
        setTimeout(function() { fb.innerHTML = '<span style="font-size:11px;color:rgba(147,197,253,0.45)">Tack!</span>'; }, 350);
      });
    });
    outer.appendChild(fb);
  }

  function tcAppendUser(text) {
    var msgs = document.getElementById("tc-messages");
    var div = document.createElement("div");
    div.innerHTML = '<div class="tc-bubble user">' + text.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;") + '</div>';
    msgs.appendChild(div);
    msgs.scrollTop = msgs.scrollHeight;
  }

  function tcClear() {
    trainChatHistory = [];
    _focusedDepContext = null;
    _focusedDep = null;
    try { localStorage.removeItem("tc-chat"); } catch(e) {}
    document.getElementById("tc-messages").innerHTML = "";
    var quick = document.getElementById("tc-quick");
    quick.innerHTML = tcStartChips();
    quick.classList.remove("tc-quick-off");
    updateContextBar();
    tcAppendBot("Hej! Jag hjälper dig hitta rätt tåg 🚂 Gör en sökning så kan jag svara på frågor om priser, platser och restider!", false);
  }

  function tcSend() {
    var input = document.getElementById("tc-input");
    var msg = input.value.trim();
    if (!msg) return;
    input.value = "";
    tcSendMessage(msg);
  }

  async function tcSendMessage(message) {
    document.getElementById("tc-quick").classList.add("tc-quick-off");
    tcAppendUser(message);

    var msgsEl = document.getElementById("tc-messages");
    var typingDiv = document.createElement("div");
    typingDiv.innerHTML = '<div class="tc-bubble bot"><div class="tc-typing"><span></span><span></span><span></span></div></div>';
    msgsEl.appendChild(typingDiv);
    msgsEl.scrollTop = msgsEl.scrollHeight;

    trainChatHistory.push({ role: "user", content: message });
    tcSaveChatHistory();

    var context = _focusedDepContext || buildDepartureContext(window._trainSearchData);
    var limited = trainChatHistory.slice(-10);

    // Skiss + ev. vald plats följer med varje fråga: servern räknar avstånden och lägger
    // in dem i prompten, så "var finns toaletten?" besvaras med vagn och rad i stället
    // för allmänt "det finns toaletter ombord".
    var sketchLayout = tcLayoutId();
    var freeSeats = tcFreeSeatsContext(message);

    var resp;
    try {
      resp = await fetch(TRAIN_CHAT_API + "/api/chat/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: limited, context: context,
                               layout: tcLayoutId(), seat: tcSeatCode(), freeSeats: freeSeats })
      });
    } catch(e) {
      typingDiv.remove();
      var errDiv = tcAppendBot("Kunde inte nå assistenten — kontrollera anslutningen.", false);
      var btn = document.createElement("button"); btn.className = "tc-retry"; btn.textContent = "↺ Försök igen";
      btn.onclick = function() { errDiv.remove(); trainChatHistory.pop(); tcSaveChatHistory(); tcSendMessage(message); };
      errDiv.appendChild(btn);
      return;
    }

    typingDiv.remove();

    if (resp.status === 429) {
      tcAppendBot("Du har ställt för många frågor — vänta en minut och försök igen.", false);
      return;
    }
    if (!resp.ok) {
      var errDiv2 = tcAppendBot("Något gick fel (fel " + resp.status + ").", false);
      var btn2 = document.createElement("button"); btn2.className = "tc-retry"; btn2.textContent = "↺ Försök igen";
      btn2.onclick = function() { errDiv2.remove(); trainChatHistory.pop(); tcSaveChatHistory(); tcSendMessage(message); };
      errDiv2.appendChild(btn2);
      return;
    }

    // Fallback for browsers without ReadableStream support
    if (!resp.body || typeof resp.body.getReader !== "function") {
      try {
        var fbResp = await fetch(TRAIN_CHAT_API + "/api/chat", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ messages: limited, context: context,
                               layout: tcLayoutId(), seat: tcSeatCode(), freeSeats: freeSeats })
        });
        var fbData = await fbResp.json();
        var fbReply = fbData.reply || fbData.error || "Inget svar.";
        trainChatHistory.push({ role: "assistant", content: fbReply });
        tcSaveChatHistory();
        var fbOuter = tcAppendBot(fbReply, true);
        tcInjectTrainImages(fbReply, fbOuter);
        tcMaybeAppendSketch(message, sketchLayout, fbOuter);
        tcMaybeAppendSeatButtons(message, fbOuter);
        tcAddFollowupChips(fbReply, fbOuter);
      } catch(_) { tcAppendBot("Kunde inte nå assistenten.", false); }
      return;
    }

    // Create streaming bubble
    var outer = document.createElement("div");
    var bubble = document.createElement("div");
    bubble.className = "tc-bubble bot";
    outer.appendChild(bubble);
    msgsEl.appendChild(outer);

    var fullText = "";
    var reader = resp.body.getReader();
    var decoder = new TextDecoder();
    var buf = "";
    var streamDone = false;

    try {
      while (!streamDone) {
        var chunk = await reader.read();
        if (chunk.done) break;
        buf += decoder.decode(chunk.value, { stream: true });
        var lines = buf.split("\n");
        buf = lines.pop();
        for (var i = 0; i < lines.length; i++) {
          var line = lines[i].trim();
          if (!line.startsWith("data:")) continue;
          var data = line.slice(5).trim();
          if (data === "[DONE]") { streamDone = true; break; }
          try {
            var token = JSON.parse(data);
            if (token.startsWith("[ERR]")) throw new Error(token.slice(5));
            fullText += token;
            bubble.textContent = fullText;
            msgsEl.scrollTop = msgsEl.scrollHeight;
          } catch(parseErr) {
            if (parseErr.message && !parseErr.message.startsWith("JSON")) throw parseErr;
          }
        }
      }
    } catch(streamErr) {
      if (!fullText) {
        outer.remove();
        var errDiv3 = tcAppendBot(streamErr.message || "Kunde inte nå assistenten.", false);
        var btn3 = document.createElement("button"); btn3.className = "tc-retry"; btn3.textContent = "↺ Försök igen";
        btn3.onclick = function() { errDiv3.remove(); trainChatHistory.pop(); tcSaveChatHistory(); tcSendMessage(message); };
        errDiv3.appendChild(btn3);
        return;
      }
    }

    // Finalize bubble
    bubble.innerHTML = tcMarkdown(fullText);
    tcInjectTrainImages(fullText, outer);
    tcHighlightDepartures(fullText);
    tcMaybeAppendSketch(message, sketchLayout, outer);
    tcMaybeAppendSeatButtons(message, outer);
    tcAddFeedback(outer);
    tcAddFollowupChips(fullText, outer);
    msgsEl.scrollTop = msgsEl.scrollHeight;

    trainChatHistory.push({ role: "assistant", content: fullText });
    tcSaveChatHistory();
  }

  /* ── Vagnsskiss i chatten ────────────────────────────────────────────────────
     Layout-id kommer från den avgång användaren fokuserat, annars från platskartan
     om den är öppen. Skissen ritas ur serverns /api/train-layout — samma källa som
     platskartan och AI:ns prompt, så bild och svar kan inte säga olika saker. */
  var _focusedLayout = '';
  var _tcLayoutCache = {};

  function tcLayoutId() {
    if (window._seatState && window._seatState.layout) return window._seatState.layout;
    return _focusedLayout || '';
  }

  function tcSeatCode() {
    return (window._seatState && window._seatState.selected) || '';
  }

  var TC_SKETCH_RE = /(toalett|toa\b|wc|handikapp|rullstol|bistro|bistron|café|cafe|kiosk|servering|restaurang|matvagn|närmast|narmast|vilken plats|var sitter|vagnsskiss|skiss)/i;

  function tcFetchLayout(id, cb) {
    if (!id) { cb(null); return; }
    if (_tcLayoutCache[id]) { cb(_tcLayoutCache[id]); return; }
    fetch(TRAIN_CHAT_API + '/api/train-layout?layout=' + encodeURIComponent(id))
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) { if (j && j.wagons) { _tcLayoutCache[id] = j; cb(j); } else cb(null); })
      .catch(function () { cb(null); });
  }

  /** Ritar tåget som en rad vagnar med toalett/bistro utmärkta. */
  function tcBuildSketch(ld, seatCode) {
    var seatWagon = 0, seatRow = 0;
    var m = /^(\d+)-(\d+)([A-Za-z0-9]?)$/.exec(seatCode || '');
    if (m) { seatWagon = parseInt(m[1], 10); seatRow = parseInt(m[2], 10); }

    var wrap = document.createElement('div');
    wrap.className = 'tc-sketch';
    var html = '<div class="tc-sketch-title">🚆 ' + ld.trainName + ' — vagnsskiss</div><div class="tc-sketch-row">';
    ld.wagons.forEach(function (w) {
      var icons = '', bistroAfter = null;
      ld.facilities.forEach(function (f) {
        if (f.wagon !== w.number) return;
        if (f.type === 'TOALETT') icons += '🚻';
        else if (f.type === 'RULLSTOL') icons += '♿';
        else if (f.type === 'BISTRO') { if (f.row > w.rowTo) bistroAfter = f; else icons += '☕'; }
      });
      var isSeatWagon = w.number === seatWagon;
      html += '<div class="tc-sk-wagon' + (isSeatWagon ? ' mine' : '') + '">' +
                '<b>Vagn ' + w.number + '</b>' +
                '<span>' + w.seatClass + '</span>' +
                '<span class="tc-sk-rows">rad ' + w.rowFrom + '–' + w.rowTo + '</span>' +
                '<span class="tc-sk-icons">' + (icons || '&nbsp;') + '</span>' +
                (isSeatWagon ? '<span class="tc-sk-you">din plats rad ' + seatRow + '</span>' : '') +
              '</div>';
      if (bistroAfter) html += '<div class="tc-sk-bistro">☕ ' + bistroAfter.label + '</div>';
    });
    html += '</div><div class="tc-sketch-note">' + (ld.note || '') +
            ' · Förenklad skiss — ordning och ände stämmer, inte exakta mått.' +
            '<br><span class="tc-sketch-src">Källa: <code>vagnskiss://' + ld.id + '</code>' +
            ' — samma skiss som platskartan och AI-svaret bygger på</span></div>';
    wrap.innerHTML = html;
    return wrap;
  }

  var TC_SEAT_RE = /(ledig|föreslå|foresla|boka|välj|valj|plats närmast|plats narmast|närmast bistron|narmast bistron|fönsterplats|fonsterplats|gångplats|gangplats|bordsplats|vid bord)/i;

  /** Lediga platser (från platskartans egen tillgänglighet) att skicka med frågan. */
  function tcFreeSeatsContext(question) {
    if (!TC_SEAT_RE.test(question || '') || typeof window.mptFreeSeatsText !== 'function') return '';
    var wantWindow = /(fönster|fonster)/i.test(question);
    var wantTable  = /(bord)/i.test(question);
    var type = /(toalett|toa\b|wc)/i.test(question) ? 'TOALETT' : 'BISTRO';
    try {
      var txt = window.mptFreeSeatsText(type, { window: wantWindow, table: wantTable });
      if (!txt) return '';
      return 'Lediga platser närmast ' + (type === 'TOALETT' ? 'toaletten' : 'bistron') +
             (wantWindow ? ' (endast fönsterplatser)' : '') + (wantTable ? ' (endast bordsplatser)' : '') +
             ': ' + txt;
    } catch (e) { return ''; }
  }

  /** Klickbara förslag under svaret — väljer platsen direkt i platskartan. */
  function tcMaybeAppendSeatButtons(question, container) {
    if (!container || !TC_SEAT_RE.test(question || '') || typeof window.mptFreeSeatsNear !== 'function') return;
    var wantWindow = /(fönster|fonster)/i.test(question);
    var wantTable  = /(bord)/i.test(question);
    var type = /(toalett|toa\b|wc)/i.test(question) ? 'TOALETT' : 'BISTRO';
    var seats;
    try { seats = window.mptFreeSeatsNear(type, { window: wantWindow, table: wantTable }, 3); }
    catch (e) { return; }
    if (!seats || !seats.length) return;

    var wrap = document.createElement('div');
    wrap.className = 'tc-seat-picks';
    wrap.innerHTML = '<div class="tc-seat-picks-lbl">Välj direkt:</div>';
    seats.forEach(function (s) {
      var b = document.createElement('button');
      b.className = 'tc-seat-pick';
      b.textContent = 'Vagn ' + s.wagon + ' · ' + s.row + s.col +
                      ' (' + (s.window ? 'fönster' : 'gång') + (s.table ? ', bord' : '') + ')';
      b.onclick = function () { window.mptSelectSeat(s.seat); };
      wrap.appendChild(b);
    });
    container.appendChild(wrap);
  }

  function tcMaybeAppendSketch(question, layoutId, container) {
    if (!container || !TC_SKETCH_RE.test(question || '')) return;
    tcFetchLayout(layoutId, function (ld) {
      if (ld) container.appendChild(tcBuildSketch(ld, tcSeatCode()));
    });
  }

  window.tcFocusDeparture = function(btn) {
    _focusedLayout = btn.getAttribute('data-layout') || _focusedLayout;
    var depTime    = btn.getAttribute('data-dep-time')    || '';
    var arrTime    = btn.getAttribute('data-arr-time')    || '';
    var trainId    = btn.getAttribute('data-train-id')    || '';
    var model      = btn.getAttribute('data-model')       || '';
    var dest       = btn.getAttribute('data-destination') || '';
    var price      = btn.getAttribute('data-price')       || '';
    var seatsLeft  = parseInt(btn.getAttribute('data-seats-left'),  10) || 0;
    var travelMins = parseInt(btn.getAttribute('data-travel-mins'), 10) || 0;

    var byte       = btn.getAttribute('data-byte') || '';
    var slutstation = btn.getAttribute('data-final') || '';
    var fromName = (window._trainSearchData && window._trainSearchData.fromName) || '';
    var toName   = (window._trainSearchData && window._trainSearchData.toName)   || dest;
    var date     = (window._trainSearchData && window._trainSearchData.date)     || '';

    var dur = '';
    if (travelMins > 0) {
      var h = Math.floor(travelMins / 60);
      var m = travelMins % 60;
      dur = (h > 0 ? h + 'h' : '') + (m > 0 ? ' ' + m + 'min' : '');
    }
    var timeRange = depTime + (arrTime ? ' – ' + arrTime : '');

    _focusedDepContext =
      'Användaren tittar på en SPECIFIK avgång:\n' +
      'Sträcka: ' + fromName + ' → ' + toName + '\n' +
      'Datum: ' + date + '\n' +
      'Avgångstid: ' + depTime + (arrTime ? ', ankomst: ' + arrTime : '') + '\n' +
      (dur ? 'Restid: ' + dur + ' · ' + (byte ? '1 byte i ' + byte : 'direkttåg, 0 byten') + '\n' : '') +
      'Tåg: ' + trainId + (model ? ' (' + model + ')' : '') + '\n' +
      (slutstation ? 'Tåget fortsätter mot ' + slutstation + ' — resenären kliver av i ' + toName + '\n' : '') +
      'Pris: ' + price + '\n' +
      (seatsLeft > 0 ? 'MiniPris-platser kvar: ' + seatsLeft + '\n' : 'Inga MiniPris-platser kvar\n') +
      '\nSvara med fokus på just denna avgång.';

    _focusedDep = { depTime: depTime, arrTime: arrTime, fromName: fromName, toName: toName };

    var bar = document.getElementById('tc-context-bar');
    if (bar) {
      bar.textContent = '🚂 ' + timeRange + (dur ? ' · ' + dur : '') + ' · ' + fromName + ' → ' + toName;
      bar.style.display = 'block';
    }

    var quick = document.getElementById('tc-quick');
    if (quick) {
      quick.innerHTML =
        tcChip('167,139,250', '🪑', 'Vilken klass?', 'Vilken reseklass passar bäst för denna avgång?') +
        tcChip('56,189,248', '🛜', 'WiFi & 5G', 'Finns WiFi och 5G på ' + (model || 'detta tåg') + '?') +
        tcChip('251,146,60', '🧳', 'Bagage', 'Vad gäller för bagage på denna avgång?') +
        tcChip('52,211,153', '🗺', 'Till centrum', 'Hur lång tid tar det från ' + toName + ' centralstation till centrum?');
      quick.classList.remove('tc-quick-off');
    }

    // På telefon öppnas chatten INTE av sig själv: bottenkortet täckte klassvalet och
    // platsknappen man just fällt ut, och fokus i rutan fällde upp tangentbordet över
    // resten. Samtalet förbereds ändå (kontextbar, snabbknappar, hälsning) så det står
    // klart när man själv trycker på chattknappen. Samma brytpunkt som panelens mobilläge.
    var telefon = window.matchMedia && window.matchMedia('(max-width:640px)').matches;
    if (!telefon && !tcIsOpen()) tcSetOpen(true);

    var seatsMsg = seatsLeft > 0
      ? ' Det finns **' + seatsLeft + ' MiniPris-platser kvar**.'
      : ' Inga MiniPris-platser kvar – men du kan ändå köpa ordinarie biljett.';
    var introText =
      'Du tittar på tåget **' + timeRange + '**' +
      (model ? ' (' + model + ')' : '') +
      (dur ? ' — restid ' + dur : '') +
      ', från **' + fromName + '** till **' + toName + '**' +
      (byte ? ' med byte i **' + byte + '**' : '') + '.' +
      (price ? ' Pris från **' + price.replace('från ', '') + '**.' : '') +
      seatsMsg +
      '\n\nVad vill du veta mer om denna resa?';
    var introBubbla = tcAppendBot(introText, false);
    tcAppendRouteMap(introBubbla, byte ? [fromName, byte, toName] : [fromName, toName]);

    var inp = document.getElementById('tc-input');
    if (inp) {
      inp.placeholder = 'Fråga om tåget ' + timeRange + '…';
      if (!telefon) inp.focus();
    }
  };

  initTrainChat();
  tcVackNarSplashenSlappt();
})();
