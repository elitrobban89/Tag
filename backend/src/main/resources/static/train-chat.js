(function () {
  var TRAIN_CHAT_API = window.TRAIN_API_URL || "";
  var trainChatHistory = [];
  window._trainSearchData = null;

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
        background:rgba(7,13,31,0.82);
        backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px);
        border:1px solid rgba(96,165,250,0.2);border-radius:20px;
        box-shadow:0 8px 48px rgba(0,0,0,.7),0 0 0 1px rgba(255,255,255,0.04) inset;
        display:flex;flex-direction:column;overflow:hidden;
        font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
      }
      .tc-header {
        background:linear-gradient(135deg,rgba(30,58,138,0.9),rgba(29,78,216,0.8));
        backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
        border-bottom:1px solid rgba(96,165,250,0.15);
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
        background:rgba(12,22,50,0.75);
        backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
        border:1px solid rgba(96,165,250,0.15);
        border-radius:4px 14px 14px 14px;align-self:flex-start;color:#dbeafe;
      }
      .tc-bubble.bot strong{color:#93c5fd;}
      .tc-bubble.bot ul{margin:6px 0 2px 16px;display:flex;flex-direction:column;gap:3px;}
      .tc-bubble.bot li{list-style:disc;}
      .tc-bubble.user {
        background:linear-gradient(135deg,rgba(29,78,216,0.85),rgba(59,130,246,0.8));
        backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
        border:1px solid rgba(96,165,250,0.2);
        color:#fff;border-radius:14px 14px 4px 14px;align-self:flex-end;
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
            <svg viewBox="0 0 52 44" width="38" height="30" xmlns="http://www.w3.org/2000/svg">
              <!-- tåg-kropp -->
              <rect x="4" y="14" width="44" height="22" rx="6" fill="rgba(255,255,255,0.15)" stroke="rgba(147,197,253,0.45)" stroke-width="1.2"/>
              <!-- fönster -->
              <rect x="10" y="19" width="8" height="6" rx="2" fill="rgba(147,197,253,0.35)" stroke="rgba(147,197,253,0.4)" stroke-width="0.8"/>
              <rect x="22" y="19" width="8" height="6" rx="2" fill="rgba(147,197,253,0.35)" stroke="rgba(147,197,253,0.4)" stroke-width="0.8"/>
              <rect x="34" y="19" width="8" height="6" rx="2" fill="rgba(147,197,253,0.35)" stroke="rgba(147,197,253,0.4)" stroke-width="0.8"/>
              <!-- hjul -->
              <circle cx="13" cy="38" r="4.5" fill="#0f172a" stroke="rgba(147,197,253,0.5)" stroke-width="1.5"/>
              <circle cx="13" cy="38" r="2" fill="rgba(147,197,253,0.4)"/>
              <circle cx="39" cy="38" r="4.5" fill="#0f172a" stroke="rgba(147,197,253,0.5)" stroke-width="1.5"/>
              <circle cx="39" cy="38" r="2" fill="rgba(147,197,253,0.4)"/>
              <!-- lyktor -->
              <rect x="46" y="20" width="4" height="3" rx="1.5" fill="#fef08a"/>
              <!-- skena -->
              <line x1="2" y1="43" x2="50" y2="43" stroke="rgba(147,197,253,0.3)" stroke-width="1.5" stroke-linecap="round"/>
              <!-- blixt -->
              <path d="M24 10 L21 17 L25.5 15 L23 22" fill="#fef08a" stroke="#fef08a" stroke-width="0.4" stroke-linejoin="round"/>
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

    tcAppendBot("Hej! Jag hjälper dig hitta rätt tåg 🚂 Gör en sökning så kan jag svara på frågor om priser, platser och restider!");

    document.getElementById("tc-fab").addEventListener("click", tcToggle);
    document.getElementById("tc-close").addEventListener("click", tcToggle);
    document.getElementById("tc-send").addEventListener("click", tcSend);
    document.getElementById("tc-clear").addEventListener("click", tcClear);
    document.getElementById("tc-input").addEventListener("keydown", function(e) { if (e.key === "Enter") tcSend(); });
    document.querySelectorAll(".tc-quick-btn").forEach(function(btn) {
      btn.addEventListener("click", function() { tcSendMessage(btn.dataset.q); });
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
    var data = window._trainSearchData;
    if (data && data.fromName) {
      bar.textContent = "🔍 " + data.fromName + " → " + data.toName + "  |  📅 " + data.date + "  |  🚂 " + (data.departures ? data.departures.length : 0) + " avgångar";
      bar.style.display = "block";
    } else {
      bar.style.display = "none";
    }
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

  function tcAppendBot(text) {
    var msgs = document.getElementById("tc-messages");
    var div = document.createElement("div");
    div.innerHTML = '<div class="tc-bubble bot">' + tcMarkdown(text) + '</div>';
    msgs.appendChild(div);
    msgs.scrollTop = msgs.scrollHeight;
    return div;
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
    document.getElementById("tc-messages").innerHTML = "";
    document.getElementById("tc-quick").style.display = "flex";
    tcAppendBot("Hej! Jag hjälper dig hitta rätt tåg 🚂 Gör en sökning så kan jag svara på frågor om priser, platser och restider!");
  }

  function tcSend() {
    var input = document.getElementById("tc-input");
    var msg = input.value.trim();
    if (!msg) return;
    input.value = "";
    tcSendMessage(msg);
  }

  function tcSendMessage(message) {
    document.getElementById("tc-quick").style.display = "none";
    tcAppendUser(message);
    trainChatHistory.push({ role: "user", content: message });

    var msgs = document.getElementById("tc-messages");
    var typingDiv = document.createElement("div");
    typingDiv.innerHTML = '<div class="tc-bubble bot"><div class="tc-typing"><span></span><span></span><span></span></div></div>';
    msgs.appendChild(typingDiv);
    msgs.scrollTop = msgs.scrollHeight;

    var context = buildDepartureContext(window._trainSearchData);

    fetch(TRAIN_CHAT_API + "/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ messages: trainChatHistory, context: context })
    }).then(function(resp) {
      typingDiv.remove();
      if (resp.status === 429) {
        tcAppendBot("Du har ställt för många frågor på kort tid — vänta en minut och försök igen.");
        return;
      }
      resp.json().then(function(data) {
        var reply = data.reply || data.error || "Inget svar.";
        trainChatHistory.push({ role: "assistant", content: reply });
        tcAppendBot(reply);
      });
    }).catch(function() {
      typingDiv.remove();
      tcAppendBot("Kunde inte nå assistenten just nu — försök igen om en stund.");
    });
  }

  initTrainChat();
})();
