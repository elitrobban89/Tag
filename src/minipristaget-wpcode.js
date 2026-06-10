// ═══════════════════════════════════════════════════════
//  MINIPRISTAGET – WPCode JavaScript Snippet
//  Klistra in detta i WPCode → JavaScript Snippet
// ═══════════════════════════════════════════════════════

var MP_BACKEND = 'https://tag-k5we.onrender.com';

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
        var city = (a.city || a.town || a.village || a.municipality || a.county || '')
                   .replace(/\s+station$/i, '').trim();
        var fromEl = document.getElementById('mp-from');
        if (fromEl && city) fromEl.value = city;
        mpSetHint('', '');
        if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
        if (lbl) lbl.textContent = 'GPS';
      })
      .catch(function() {
        mpSetHint('Kunde inte hämta ortnamn – ange startort manuellt.', 'error');
        if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
        if (lbl) lbl.textContent = 'GPS';
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
      if (lbl) lbl.textContent = 'GPS';
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
  var dateOut = document.getElementById('mp-date-out');
  var dateRet = document.getElementById('mp-date-ret');
  var isRetur = document.getElementById('mp-tur') &&
                document.getElementById('mp-tur').classList.contains('active');

  var url = 'https://www.vy.se/en/train' +
    '?from='    + encodeURIComponent(from) +
    '&to='      + encodeURIComponent(till) +
    (dateOut && dateOut.value ? '&date=' + dateOut.value : '') +
    (isRetur && dateRet && dateRet.value ? '&returnDate=' + dateRet.value : '');

  window.open(url, '_blank');
}

// ── Kategoriväljare ───────────────────────────────────
function mpShowCategoryPicker() {
  var sec = document.getElementById('mp-cat-section');
  if (sec) sec.classList.add('show');
  mpSetTillHint('', '');
}

// ── AI fyller i Till-fältet ───────────────────────────
function mpGetSuggestions(category) {
  var fromEl = document.getElementById('mp-from');
  var tillEl = document.getElementById('mp-till');
  var from   = fromEl ? fromEl.value.trim() : '';

  // Markera aktiv knapp + visa laddning
  ['storstad', 'natur', 'havet'].forEach(function(c) {
    var b = document.getElementById('mp-cat-' + c);
    if (b) { b.classList.remove('active'); b.disabled = false; }
  });
  var activeBtn = document.getElementById('mp-cat-' + category.toLowerCase());
  if (activeBtn) { activeBtn.classList.add('active'); activeBtn.disabled = true; }

  mpSetTillHint('✨ Hämtar AI-förslag...', 'loading');

  fetch(MP_BACKEND + '/suggest', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ category: category, from: from })
  })
  .then(function(r) { return r.json(); })
  .then(function(data) {
    if (activeBtn) activeBtn.disabled = false;

    if (data.destination && tillEl) {
      tillEl.value = data.destination;
      tillEl.focus();
      mpSetTillHint('✅ AI föreslog "' + data.destination + '" – klicka Sök eller ändra manuellt.', 'loading');
    } else {
      mpSetTillHint('Inget förslag hittades – försök igen.', 'error');
      if (activeBtn) activeBtn.classList.remove('active');
    }
  })
  .catch(function() {
    if (activeBtn) { activeBtn.disabled = false; activeBtn.classList.remove('active'); }
    mpSetTillHint('Kunde inte hämta förslag – kontrollera anslutningen.', 'error');
  });
}

// ── Hjälpfunktioner ───────────────────────────────────
function mpSetHint(msg, type) {
  var el = document.getElementById('mp-hint');
  if (!el) return;
  el.textContent = msg;
  el.className   = 'mp-hint' + (type ? ' ' + type : '');
}

function mpSetTillHint(msg, type) {
  var el = document.getElementById('mp-till-hint');
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

  var cats = { 'mp-cat-storstad': 'Storstad', 'mp-cat-natur': 'Natur', 'mp-cat-havet': 'Havet' };
  Object.keys(cats).forEach(function(id) {
    var btn = document.getElementById(id);
    if (btn) btn.addEventListener('click', function() { mpGetSuggestions(cats[id]); });
  });

  // Enkelresa / tur-och-retur toggle
  var enkelBtn = document.getElementById('mp-enkel');
  var turBtn   = document.getElementById('mp-tur');
  var retWrap  = document.getElementById('mp-return-wrap');
  if (enkelBtn && turBtn && retWrap) {
    enkelBtn.addEventListener('click', function() {
      enkelBtn.classList.add('active');
      turBtn.classList.remove('active');
      retWrap.classList.remove('show');
    });
    turBtn.addEventListener('click', function() {
      turBtn.classList.add('active');
      enkelBtn.classList.remove('active');
      retWrap.classList.add('show');
    });
  }

  // Sätt standarddatum till idag
  var today = new Date().toISOString().split('T')[0];
  var dateOut = document.getElementById('mp-date-out');
  if (dateOut && !dateOut.value) dateOut.value = today;

  // GPS startar automatiskt vid sidladdning
  mpFetchGPS();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', mpInit);
} else {
  mpInit();
}
