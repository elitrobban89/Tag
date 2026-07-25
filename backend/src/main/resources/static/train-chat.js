(function () {
  var TRAIN_CHAT_API = window.TRAIN_API_URL || "";
  var trainChatHistory = (function(){ try{ return JSON.parse(localStorage.getItem('tc-chat')||'[]'); }catch(e){ return []; } })();
  var _focusedDepContext = null;
  var _focusedDep = null;

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
    chip.style.cssText = 'position:fixed;bottom:100px;right:24px;z-index:9997;background:linear-gradient(135deg,rgba(29,78,216,0.92),rgba(59,130,246,0.88));backdrop-filter:blur(12px);border:1px solid rgba(147,197,253,0.35);border-radius:22px;padding:8px 14px 8px 10px;display:flex;align-items:center;gap:8px;cursor:pointer;box-shadow:0 4px 20px rgba(0,0,0,0.5);font-family:-apple-system,BlinkMacSystemFont,sans-serif;font-size:12px;font-weight:600;color:#dbeafe;animation:tc-chip-in .3s ease-out;';
    chip.innerHTML = '<span style="font-size:16px">🚂</span><span>' + data.fromName + ' → ' + data.toName + '<br><span style="font-weight:400;font-size:11px;opacity:.8">' + data.departures.length + ' avgångar — fråga AI</span></span>';
    chip.addEventListener('click', function() { chip.remove(); var panel = document.getElementById('tc-panel'); if (panel && panel.style.display === 'none') { panel.style.display = 'flex'; updateContextBar(); var inp = document.getElementById('tc-input'); if (inp) inp.focus(); } });
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
      }
      .tc-fab:hover{transform:scale(1.08);box-shadow:0 6px 28px rgba(29,78,216,.8);}
      .tc-panel {
        position:fixed;bottom:96px;right:24px;z-index:9998;
        width:380px;max-height:540px;
        background:linear-gradient(160deg,rgba(20,38,80,0.4),rgba(8,16,36,0.48));
        backdrop-filter:blur(40px) saturate(160%);-webkit-backdrop-filter:blur(40px) saturate(160%);
        border:1px solid rgba(125,211,252,0.28);border-radius:20px;
        box-shadow:0 8px 48px rgba(0,0,0,.7),0 0 70px rgba(96,165,250,0.2),0 1px 0 rgba(255,255,255,0.14) inset;
        display:flex;flex-direction:column;overflow:hidden;
        font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
      }
      .tc-header {
        background:linear-gradient(135deg,rgba(37,99,235,0.72),rgba(59,130,246,0.6));
        backdrop-filter:blur(10px) saturate(150%);-webkit-backdrop-filter:blur(10px) saturate(150%);
        border-bottom:1px solid rgba(125,211,252,0.25);
        color:#fff;padding:13px 16px;
        display:flex;align-items:center;justify-content:space-between;
        font-weight:700;font-size:14px;flex-shrink:0;gap:8px;
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
      .tc-context-bar {
        padding:7px 14px;font-size:11px;font-weight:600;
        color:rgba(147,197,253,0.8);letter-spacing:0.02em;
        background:rgba(29,78,216,0.12);border-bottom:1px solid rgba(96,165,250,0.1);
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
      .tc-bubble.bot {
        background:rgba(12,22,50,0.7);
        backdrop-filter:blur(10px) saturate(150%);-webkit-backdrop-filter:blur(10px) saturate(150%);
        border:1px solid rgba(125,211,252,0.28);
        border-radius:4px 14px 14px 14px;align-self:flex-start;color:#eaf3ff;
        box-shadow:0 2px 14px rgba(0,0,0,0.28),0 0 28px rgba(96,165,250,0.3),inset 0 1px 0 rgba(255,255,255,0.08);
      }
      .tc-bubble.bot strong{color:#bfdbfe;text-shadow:0 0 10px rgba(96,165,250,0.55);}
      .tc-bubble.bot ul{margin:6px 0 2px 16px;display:flex;flex-direction:column;gap:3px;}
      .tc-bubble.bot li{list-style:disc;}
      .tc-bubble.user {
        background:linear-gradient(135deg,rgba(37,99,235,0.9),rgba(96,165,250,0.85));
        backdrop-filter:blur(10px) saturate(150%);-webkit-backdrop-filter:blur(10px) saturate(150%);
        border:1px solid rgba(147,197,253,0.4);
        color:#fff;border-radius:14px 14px 4px 14px;align-self:flex-end;
        text-shadow:0 1px 6px rgba(8,19,40,0.5);
        box-shadow:0 2px 16px rgba(59,130,246,0.55),0 0 34px rgba(96,165,250,0.4),inset 0 1px 0 rgba(255,255,255,0.22);
      }
      .tc-quick {
        padding:10px 12px 4px;display:flex;flex-wrap:wrap;gap:7px;flex-shrink:0;
        background:rgba(7,13,31,0.5);border-top:1px solid rgba(96,165,250,0.1);
      }
      .tc-quick-btn {
        background:rgba(59,130,246,0.1);border:1px solid rgba(96,165,250,0.25);color:#93c5fd;
        border-radius:20px;padding:5px 12px;font-size:12px;font-weight:600;
        cursor:pointer;transition:all .15s;white-space:nowrap;
      }
      .tc-quick-btn:hover{background:rgba(59,130,246,0.35);color:#fff;border-color:rgba(147,197,253,0.5);}
      .tc-input-row {
        display:flex;gap:8px;padding:10px 12px;
        border-top:1px solid rgba(96,165,250,0.1);
        background:rgba(7,13,31,0.5);flex-shrink:0;
      }
      .tc-input {
        flex:1;border:1px solid rgba(96,165,250,0.2);border-radius:22px;
        padding:8px 14px;font-size:13px;outline:none;
        background:rgba(12,22,50,0.6);color:#dbeafe;transition:border-color .15s,box-shadow .15s;
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
      .tc-train-imgs{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px;}
      .tc-train-img{width:100%;max-height:130px;object-fit:cover;border-radius:10px;opacity:.88;transition:opacity .2s;}
      .tc-train-img:hover{opacity:1;}
      .tc-followup-chips{display:flex;flex-wrap:wrap;gap:5px;margin-top:7px;}
      .tc-followup-chip{background:rgba(96,165,250,.09);border:1px solid rgba(96,165,250,.22);color:rgba(147,197,253,.72);font-size:11px;padding:3px 10px;border-radius:20px;cursor:pointer;transition:all .15s;}
      .tc-followup-chip:hover{background:rgba(96,165,250,.2);border-color:rgba(96,165,250,.45);color:#93c5fd;}
      @keyframes tc-dep-flash{0%,100%{box-shadow:none}30%,70%{box-shadow:0 0 0 3px rgba(96,165,250,.6),0 0 20px rgba(96,165,250,.2)}}
      .tc-dep-highlight{animation:tc-dep-flash 2s ease;}
      @keyframes tc-chip-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
      @media(max-width:400px){
        .tc-panel{width:calc(100vw - 16px);right:8px;bottom:92px;}
        .tc-fab-wrap{right:12px;bottom:12px;}
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
              <ellipse cx="87.5" cy="16.5" rx="1.8" ry="1.3" fill="#fef08a"/>
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
        </div>
      </div>
      <div class="tc-panel" id="tc-panel" style="display:none;">
        <div class="tc-header">
          <span>🚂 Tågassistenten</span>
          <div class="tc-header-actions">
            <button class="tc-header-clear" id="tc-clear">Rensa</button>
            <button class="tc-header-close" id="tc-close">✕</button>
          </div>
        </div>
        <div class="tc-context-bar" id="tc-context-bar" style="display:none;"></div>
        <div class="tc-messages" id="tc-messages"></div>
        <div class="tc-quick" id="tc-quick">
          <button class="tc-quick-btn" data-q="Vilken avgång är billigast?">💰 Billigast</button>
          <button class="tc-quick-btn" data-q="Vilken avgång är snabbast?">⚡ Snabbast</button>
          <button class="tc-quick-btn" data-q="Vilka avgångar har MiniPris-platser kvar?">🎫 Platser kvar</button>
          <button class="tc-quick-btn" data-q="Ge mig råd om vilken avgång jag ska välja">🤖 Ge råd</button>
        </div>
        <div class="tc-input-row">
          <input class="tc-input" id="tc-input" type="text" placeholder="Fråga om avgångar, priser, platser…" autocomplete="off"/>
          <button class="tc-send" id="tc-send">➤</button>
        </div>
      </div>
    `;
    document.body.appendChild(root);

    tcAppendBot("Hej! Jag hjälper dig hitta rätt tåg 🚂 Gör en sökning så kan jag svara på frågor om priser, platser och restider!", false);

    if (trainChatHistory.length > 0) {
      document.getElementById("tc-quick").style.display = "none";
      trainChatHistory.forEach(function(m) {
        if (m.role === "user") tcAppendUser(m.content);
        else if (m.role === "assistant") tcAppendBot(m.content, false);
      });
    }

    document.getElementById("tc-fab").addEventListener("click", tcToggle);
    document.getElementById("tc-close").addEventListener("click", tcToggle);
    document.getElementById("tc-send").addEventListener("click", tcSend);
    document.getElementById("tc-clear").addEventListener("click", tcClear);
    document.getElementById("tc-input").addEventListener("keydown", function(e) { if (e.key === "Enter") tcSend(); });
    document.getElementById("tc-quick").addEventListener("click", function(e) {
      var btn = e.target.closest(".tc-quick-btn");
      if (btn) tcSendMessage(btn.dataset.q);
    });
  }

  function tcToggle() {
    var panel = document.getElementById("tc-panel");
    var open = panel.style.display === "none";
    panel.style.display = open ? "flex" : "none";
    if (open) {
      updateContextBar();
      document.getElementById("tc-input").focus();
    }
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
    quick.innerHTML =
      '<button class="tc-quick-btn" data-q="Vilken avgång är billigast?">💰 Billigast</button>' +
      '<button class="tc-quick-btn" data-q="Vilken avgång är snabbast?">⚡ Snabbast</button>' +
      '<button class="tc-quick-btn" data-q="Vilka avgångar har MiniPris-platser kvar?">🎫 Platser kvar</button>' +
      '<button class="tc-quick-btn" data-q="Ge mig råd om vilken avgång jag ska välja">🤖 Ge råd</button>';
    quick.style.display = "flex";
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
    document.getElementById("tc-quick").style.display = "none";
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

    var resp;
    try {
      resp = await fetch(TRAIN_CHAT_API + "/api/chat/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: limited, context: context,
                               layout: tcLayoutId(), seat: tcSeatCode() })
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
                               layout: tcLayoutId(), seat: tcSeatCode() })
        });
        var fbData = await fbResp.json();
        var fbReply = fbData.reply || fbData.error || "Inget svar.";
        trainChatHistory.push({ role: "assistant", content: fbReply });
        tcSaveChatHistory();
        var fbOuter = tcAppendBot(fbReply, true);
        tcInjectTrainImages(fbReply, fbOuter);
        tcMaybeAppendSketch(message, sketchLayout, fbOuter);
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
      (dur ? 'Restid: ' + dur + ' · 0 byten\n' : '') +
      'Tåg: ' + trainId + (model ? ' (' + model + ')' : '') + '\n' +
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
        '<button class="tc-quick-btn" data-q="Vilken reseklass passar bäst för denna avgång?">🪑 Vilken klass?</button>' +
        '<button class="tc-quick-btn" data-q="Finns WiFi och 5G på ' + (model || 'detta tåg') + '?">🛜 WiFi & 5G</button>' +
        '<button class="tc-quick-btn" data-q="Vad gäller för bagage på denna avgång?">🧳 Bagage</button>' +
        '<button class="tc-quick-btn" data-q="Hur lång tid tar det från ' + toName + ' centralstation till centrum?">🗺 Till centrum</button>';
      quick.style.display = 'flex';
    }

    var panel = document.getElementById('tc-panel');
    if (panel && panel.style.display === 'none') panel.style.display = 'flex';

    var seatsMsg = seatsLeft > 0
      ? ' Det finns **' + seatsLeft + ' MiniPris-platser kvar**.'
      : ' Inga MiniPris-platser kvar – men du kan ändå köpa ordinarie biljett.';
    var introText =
      'Du tittar på tåget **' + timeRange + '**' +
      (model ? ' (' + model + ')' : '') +
      (dur ? ' — restid ' + dur : '') +
      ', från **' + fromName + '** till **' + toName + '**.' +
      (price ? ' Pris från **' + price.replace('från ', '') + '**.' : '') +
      seatsMsg +
      '\n\nVad vill du veta mer om denna resa?';
    tcAppendBot(introText, false);

    var inp = document.getElementById('tc-input');
    if (inp) {
      inp.placeholder = 'Fråga om tåget ' + timeRange + '…';
      inp.focus();
    }
  };

  initTrainChat();
})();
