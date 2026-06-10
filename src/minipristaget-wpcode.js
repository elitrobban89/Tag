// ═══════════════════════════════════════════════════════
//  MINIPRISTAGET – WPCode JavaScript Snippet
//  Klistra in detta i WPCode → JavaScript Snippet
// ═══════════════════════════════════════════════════════

// !! Byt ut URL:en nedan mot din Render-tjänsts adress !!
var MP_BACKEND = 'https://DITT-APP-NAMN.onrender.com';

var mpUserLat = null, mpUserLon = null;

// ── GPS: hämta position och fyll i Från-fältet ────────
function mpFetchGPS() {
  if (!navigator.geolocation) {
    mpSetHint('GPS stöds inte av din webbläsare – ange startort manuellt.', 'error');
    return;
  }

  var btn = document.getElementById('mp-gps-btn');
  var lbl = document.getElementById('mp-gps-label');
  if (btn) { btn.disabled = true; btn.classList.add('fetching'); }
  if (lbl) lbl.textContent = 'Hämtar...';
  mpSetHint('📡 Hämtar position – godkänn platsdelning om du tillfrågas...', 'loading');

  navigator.geolocation.getCurrentPosition(
    function(pos) {
      mpUserLat = pos.coords.latitude;
      mpUserLon = pos.coords.longitude;

      fetch(
        'https://nominatim.openstreetmap.org/reverse?lat=' + mpUserLat +
        '&lon=' + mpUserLon + '&format=json&accept-language=sv'
      )
      .then(function(r) { return r.json(); })
      .then(function(data) {
        var a    = data.address || {};
        var city = a.city || a.town || a.village || a.municipality || a.county || '';
        var fromEl = document.getElementById('mp-from');
        if (fromEl && city) fromEl.value = city;
        mpSetHint('', '');
        if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
        if (lbl) lbl.textContent = 'Hämta GPS';
      })
      .catch(function() {
        mpSetHint('Kunde inte hämta ortnamn – ange startort manuellt.', 'error');
        if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
        if (lbl) lbl.textContent = 'Försök igen';
      });
    },
    function(err) {
      var msgs = {
        1: '🔒 Åtkomst nekad – tillåt platsdelning i webbläsarens inställningar.',
        2: '📵 Position ej tillgänglig.',
        3: '⏱ Timeout – tryck igen eller ange startort manuellt.'
      };
      mpSetHint(msgs[err.code] || 'GPS-fel (kod ' + err.code + ').', 'error');
      if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
      if (lbl) lbl.textContent = 'Försök igen';
    },
    { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 }
  );
}

// ── Sök-knapp ─────────────────────────────────────────
function mpOnSearch() {
  var tillEl = document.getElementById('mp-till');
  var till   = tillEl ? tillEl.value.trim() : '';

  if (till) {
    var fromEl = document.getElementById('mp-from');
    mpOpenBooking(fromEl ? fromEl.value.trim() : '', till);
  } else {
    mpShowCategoryPicker();
  }
}

function mpOpenBooking(from, till) {
  var url = 'https://www.vy.se/en/train?from=' +
            encodeURIComponent(from) + '&to=' + encodeURIComponent(till);
  window.open(url, '_blank');
}

// ── Kategoriväljare ───────────────────────────────────
function mpShowCategoryPicker() {
  var sec = document.getElementById('mp-cat-section');
  if (sec) sec.classList.add('show');

  var sug = document.getElementById('mp-suggestions');
  if (sug) { sug.innerHTML = ''; sug.classList.remove('show'); }

  ['storstad', 'natur', 'strand'].forEach(function(c) {
    var b = document.getElementById('mp-cat-' + c);
    if (b) b.classList.remove('active');
  });
}

// ── AI-förslag från Groq ──────────────────────────────
function mpGetSuggestions(category) {
  var fromEl = document.getElementById('mp-from');
  var sugEl  = document.getElementById('mp-suggestions');
  var from   = fromEl ? fromEl.value.trim() : '';

  ['storstad', 'natur', 'strand'].forEach(function(c) {
    var b = document.getElementById('mp-cat-' + c);
    if (b) b.classList.remove('active');
  });
  var activeBtn = document.getElementById('mp-cat-' + category.toLowerCase());
  if (activeBtn) activeBtn.classList.add('active');

  sugEl.innerHTML = '<p class="mp-loading">✨ Söker AI-förslag...</p>';
  sugEl.classList.add('show');
  sugEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  fetch(MP_BACKEND + '/suggest', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ category: category, from: from })
  })
  .then(function(r) { return r.json(); })
  .then(function(data) {
    if (!data.suggestions || !data.suggestions.length) {
      sugEl.innerHTML = '<p class="mp-error-msg">Inga förslag hittades – försök igen.</p>';
      return;
    }
    var html = '<div class="mp-suggestion-cards">';
    data.suggestions.forEach(function(s) {
      var safe = s.namn.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
      html +=
        '<div class="mp-sug-card">' +
          '<div class="mp-sug-name">' + s.namn + '</div>' +
          '<div class="mp-sug-desc">'  + s.beskrivning + '</div>' +
          '<div class="mp-sug-footer">' +
            '<span class="mp-sug-time">🚂 ' + s.restid + '</span>' +
            '<button class="mp-sug-pick" onclick="mpPickDestination(\'' + safe + '\')">' +
              'Välj destination' +
            '</button>' +
          '</div>' +
        '</div>';
    });
    html += '</div>';
    sugEl.innerHTML = html;
  })
  .catch(function() {
    sugEl.innerHTML =
      '<p class="mp-error-msg">Kunde inte hämta förslag – kontrollera anslutningen.</p>';
  });
}

function mpPickDestination(destination) {
  var tillEl = document.getElementById('mp-till');
  if (tillEl) tillEl.value = destination;

  document.getElementById('mp-cat-section').classList.remove('show');
  var sugEl = document.getElementById('mp-suggestions');
  sugEl.innerHTML = '';
  sugEl.classList.remove('show');

  var fromEl = document.getElementById('mp-from');
  mpOpenBooking(fromEl ? fromEl.value.trim() : '', destination);
}

// ── Hjälpfunktioner ───────────────────────────────────
function mpSetHint(msg, type) {
  var el = document.getElementById('mp-hint');
  if (!el) return;
  el.textContent = msg;
  el.className   = 'mp-hint' + (type ? ' ' + type : '');
}

// ── Starta ────────────────────────────────────────────
function mpInit() {
  var gpsBtn = document.getElementById('mp-gps-btn');
  if (gpsBtn) gpsBtn.addEventListener('click', mpFetchGPS);

  var searchBtn = document.getElementById('mp-search-btn');
  if (searchBtn) searchBtn.addEventListener('click', mpOnSearch);

  var cats = { 'mp-cat-storstad': 'Storstad', 'mp-cat-natur': 'Natur', 'mp-cat-strand': 'Strand' };
  Object.keys(cats).forEach(function(id) {
    var btn = document.getElementById(id);
    if (btn) btn.addEventListener('click', function() { mpGetSuggestions(cats[id]); });
  });

  // GPS startar automatiskt vid sidladdning
  mpFetchGPS();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', mpInit);
} else {
  mpInit();
}
