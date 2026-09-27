/* Linjekartan — "Våra linjer" (MiniPrisTåget)
 * (c) 2026 Robert Andersson Kopler. Alla rättigheter förbehållna.
 *
 * Huvudlinjerna i appen ritade på en karta, färgade per bolag, i stil med VR:s
 * destinationskarta. Sökningen täcker ALLA tåg i Trafikverkets data — kartan visar de
 * linjer som går mest, inte en gräns för vad som går att söka.
 *
 * Leaflet laddas först när kartan öppnas (ingen kostnad för den som aldrig öppnar den).
 * Kartbilderna är Esris mörkgrå bas + ortnamnslager (ingen nyckel krävs, attribution
 * enligt villkoren). CARTO:s mörka tema provades först men kräver numera API-nyckel —
 * varje ruta var "API KEY REQUIRED" (2026-09-27).
 */
(function () {
  'use strict';

  // Stationer: namnet måste vara det Trafikverket använder, annars hittar sökningen den inte
  var ST = {
    'Stockholm C': [59.330, 18.058], 'Arlanda C': [59.649, 17.929], 'Uppsala C': [59.858, 17.646],
    'Gävle C': [60.676, 17.150], 'Söderhamn': [61.304, 17.059], 'Hudiksvall': [61.728, 17.105],
    'Sundsvall C': [62.387, 17.306], 'Härnösand': [62.633, 17.938], 'Örnsköldsvik C': [63.290, 18.716],
    'Umeå C': [63.830, 20.264], 'Luleå': [65.585, 22.157], 'Boden C': [65.825, 21.689],
    'Kiruna': [67.858, 20.225], 'Ljusdal': [61.829, 16.091], 'Ånge': [62.524, 15.659],
    'Östersund C': [63.172, 14.643], 'Borlänge C': [60.483, 15.435], 'Falun C': [60.603, 15.636],
    'Mora': [61.005, 14.540], 'Västerås C': [59.609, 16.548], 'Örebro C': [59.270, 15.212],
    'Hallsberg': [59.066, 15.110], 'Karlstad C': [59.378, 13.499], 'Kristinehamn': [59.310, 14.108],
    'Arvika': [59.654, 12.586], 'Södertälje Syd': [59.162, 17.645], 'Katrineholm C': [58.996, 16.206],
    'Norrköping C': [58.596, 16.183], 'Linköping C': [58.417, 15.625], 'Mjölby': [58.325, 15.132],
    'Nässjö C': [57.653, 14.694], 'Jönköping C': [57.784, 14.163], 'Alvesta': [56.899, 14.556],
    'Växjö': [56.878, 14.806], 'Kalmar C': [56.664, 16.356], 'Hultsfred': [57.488, 15.844],
    'Karlskrona C': [56.166, 15.586], 'Kristianstad C': [56.031, 14.153], 'Hässleholm C': [56.158, 13.766],
    'Lund C': [55.705, 13.187], 'Malmö C': [55.609, 13.000], 'Helsingborg C': [56.043, 12.695],
    'Halmstad C': [56.669, 12.864], 'Varberg': [57.105, 12.253], 'Göteborg C': [57.709, 11.973],
    'Skövde C': [58.390, 13.847], 'Falköping C': [58.175, 13.553], 'Herrljunga': [58.078, 13.024],
    'Trollhättan C': [58.283, 12.285], 'Uddevalla C': [58.349, 11.936], 'Borås C': [57.720, 12.938],
    'Oslo S': [59.911, 10.753], 'Köpenhamn H': [55.673, 12.565]
  };

  // Linjer: bolag + stationerna i ordning. Färgerna är desamma som märkena i avgångslistan.
  var LINJER = [
    { bolag: 'SJ', namn: 'Stockholm – Göteborg (Snabbtåg)',
      via: ['Stockholm C', 'Södertälje Syd', 'Katrineholm C', 'Hallsberg', 'Skövde C', 'Falköping C', 'Herrljunga', 'Göteborg C'] },
    { bolag: 'VR', namn: 'Stockholm – Göteborg (VR Snabbtåg)',
      via: ['Stockholm C', 'Södertälje Syd', 'Skövde C', 'Göteborg C'] },
    { bolag: 'SJ', namn: 'Stockholm – Malmö – Köpenhamn',
      via: ['Stockholm C', 'Södertälje Syd', 'Norrköping C', 'Linköping C', 'Mjölby', 'Nässjö C', 'Alvesta', 'Hässleholm C', 'Lund C', 'Malmö C', 'Köpenhamn H'] },
    { bolag: 'SJ', namn: 'Stockholm – Sundsvall – Umeå',
      via: ['Stockholm C', 'Arlanda C', 'Uppsala C', 'Gävle C', 'Söderhamn', 'Hudiksvall', 'Sundsvall C', 'Härnösand', 'Örnsköldsvik C', 'Umeå C'] },
    { bolag: 'SJ', namn: 'Stockholm – Östersund',
      via: ['Stockholm C', 'Arlanda C', 'Uppsala C', 'Gävle C', 'Ljusdal', 'Ånge', 'Östersund C'] },
    { bolag: 'SJ', namn: 'Stockholm – Karlstad – Oslo',
      via: ['Stockholm C', 'Södertälje Syd', 'Katrineholm C', 'Hallsberg', 'Kristinehamn', 'Karlstad C', 'Arvika', 'Oslo S'] },
    { bolag: 'SJ', namn: 'Stockholm – Falun – Mora',
      via: ['Stockholm C', 'Arlanda C', 'Uppsala C', 'Borlänge C', 'Falun C', 'Mora'] },
    { bolag: 'SJ', namn: 'Göteborg – Malmö',
      via: ['Göteborg C', 'Varberg', 'Halmstad C', 'Helsingborg C', 'Lund C', 'Malmö C'] },
    { bolag: 'Öresundståg', namn: 'Göteborg – Köpenhamn',
      via: ['Göteborg C', 'Varberg', 'Halmstad C', 'Helsingborg C', 'Lund C', 'Malmö C', 'Köpenhamn H'] },
    { bolag: 'Öresundståg', namn: 'Malmö – Kalmar / Karlskrona',
      via: ['Malmö C', 'Lund C', 'Hässleholm C', 'Alvesta', 'Växjö', 'Kalmar C'] },
    { bolag: 'Öresundståg', namn: 'Malmö – Kristianstad – Karlskrona',
      via: ['Malmö C', 'Lund C', 'Hässleholm C', 'Kristianstad C', 'Karlskrona C'] },
    { bolag: 'Mälartåg', namn: 'Stockholm – Västerås – Örebro',
      via: ['Stockholm C', 'Västerås C', 'Örebro C', 'Hallsberg'] },
    { bolag: 'Mälartåg', namn: 'Stockholm – Norrköping – Linköping',
      via: ['Stockholm C', 'Södertälje Syd', 'Norrköping C', 'Linköping C'] },
    { bolag: 'Västtrafik', namn: 'Göteborg – Trollhättan – Uddevalla',
      via: ['Göteborg C', 'Trollhättan C', 'Uddevalla C'] },
    { bolag: 'Västtrafik', namn: 'Göteborg – Borås',
      via: ['Göteborg C', 'Borås C'] },
    { bolag: 'Västtrafik', namn: 'Göteborg – Karlstad (Vänertåg)',
      via: ['Göteborg C', 'Trollhättan C', 'Karlstad C'] },
    { bolag: 'Regionaltåg', namn: 'Linköping – Kalmar (Kustpilen)',
      via: ['Linköping C', 'Hultsfred', 'Kalmar C'] },   // Stångådalsbanan — Västervik är en sidobana
    { bolag: 'Regionaltåg', namn: 'Nässjö – Jönköping',
      via: ['Nässjö C', 'Jönköping C'] },
    { bolag: 'Nattåg', namn: 'Stockholm – Luleå – Kiruna (nattåg)',
      via: ['Stockholm C', 'Uppsala C', 'Gävle C', 'Sundsvall C', 'Umeå C', 'Boden C', 'Luleå', 'Kiruna'] }
  ];

  var FARG = { 'SJ': '#dc2626', 'VR': '#16a34a', 'Öresundståg': '#a78bfa', 'Mälartåg': '#14b8a6',
               'Västtrafik': '#38bdf8', 'Regionaltåg': '#f59e0b', 'Nattåg': '#e879f9' };

  var karta = null, lager = {}, avstangda = {};

  function laddaLeaflet(klar) {
    if (window.L) return klar();
    var css = document.createElement('link');
    css.rel = 'stylesheet';
    css.href = 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css';
    document.head.appendChild(css);
    var js = document.createElement('script');
    js.src = 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js';
    js.onload = klar;
    js.onerror = function () {
      document.getElementById('lk-karta').innerHTML =
        '<div class="lk-fel">Kartan kunde inte laddas just nu. Sökningen fungerar som vanligt.</div>';
    };
    document.head.appendChild(js);
  }

  function byggKarta() {
    // zoomSnap 0.25: med hela steg blev Sverige frimärksstort på en smal telefon
    karta = L.map('lk-karta', { zoomControl: true, attributionControl: true, scrollWheelZoom: true, zoomSnap: 0.25 })
      .setView([61.2, 15.4], 5);
    var ESRI = 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/';
    L.tileLayer(ESRI + 'World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 12, minZoom: 3,
      attribution: 'Karta &copy; <a href="https://www.esri.com/">Esri</a>, HERE, Garmin, &copy; OpenStreetMap'
    }).addTo(karta);
    // Ortnamnen i ett eget lager OVANPÅ linjerna, så städerna går att läsa
    karta.createPane('etiketter');
    karta.getPane('etiketter').style.zIndex = 450;
    karta.getPane('etiketter').style.pointerEvents = 'none';
    L.tileLayer(ESRI + 'World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 12, minZoom: 3, pane: 'etiketter'
    }).addTo(karta);

    // Parallella linjer på samma spår förskjuts lite i sidled så båda syns (SJ + VR Sthlm–Gbg)
    var raknare = {};
    LINJER.forEach(function (l) {
      var pts = l.via.map(function (n) { return ST[n]; }).filter(Boolean);
      var nyckel = l.via[0] + '|' + l.via[l.via.length - 1];
      var n = raknare[nyckel] = (raknare[nyckel] || 0) + 1;
      var skift = (n - 1) * 0.035;
      var linje = L.polyline(pts.map(function (p) { return [p[0] + skift, p[1] + skift]; }), {
        color: FARG[l.bolag] || '#94a3b8', weight: 4, opacity: .88, lineCap: 'round',
        dashArray: l.bolag === 'Nattåg' ? '2 8' : null
      }).bindTooltip('<b>' + l.bolag + '</b> · ' + l.namn, { sticky: true });
      (lager[l.bolag] = lager[l.bolag] || []).push(linje);
      linje.addTo(karta);
    });

    // Stationer: prick + popup med "Res härifrån" / "Res hit"
    var medLinje = {};
    LINJER.forEach(function (l) { l.via.forEach(function (n) { medLinje[n] = (medLinje[n] || 0) + 1; }); });
    Object.keys(ST).forEach(function (namn) {
      var stor = medLinje[namn] >= 3;
      L.circleMarker(ST[namn], {
        radius: stor ? 6 : 4, color: '#fff', weight: 2, fillColor: stor ? '#60a5fa' : '#1e3a8a', fillOpacity: 1
      }).bindPopup(
        '<div class="lk-pop"><b>' + namn + '</b>' +
        '<button type="button" data-fran="' + namn + '">Res härifrån</button>' +
        '<button type="button" data-till="' + namn + '">Res hit</button></div>'
      ).addTo(karta);
    });
    karta.on('popupopen', function (e) {
      var el = e.popup.getElement();
      el.querySelectorAll('button').forEach(function (b) {
        b.onclick = function () {
          if (b.dataset.fran) document.getElementById('from').value = b.dataset.fran;
          if (b.dataset.till) document.getElementById('to').value = b.dataset.till;
          stang();
          var tomt = !document.getElementById('from').value || !document.getElementById('to').value;
          var falt = document.getElementById(tomt && b.dataset.fran ? 'to' : 'from');
          if (tomt) falt.focus();
        };
      });
    });
  }

  function ritaForklaring() {
    var ruta = document.getElementById('lk-forklaring');
    ruta.innerHTML = Object.keys(FARG).map(function (b) {
      return '<button type="button" class="lk-bolag" data-bolag="' + b + '" style="--f:' + FARG[b] + '">' +
             '<span class="lk-streck"></span>' + b + '</button>';
    }).join('');
    ruta.querySelectorAll('.lk-bolag').forEach(function (knapp) {
      knapp.onclick = function () {
        var b = knapp.dataset.bolag;
        avstangda[b] = !avstangda[b];
        knapp.classList.toggle('av', avstangda[b]);
        (lager[b] || []).forEach(function (l) { avstangda[b] ? karta.removeLayer(l) : l.addTo(karta); });
      };
    });
  }

  function oppna() {
    var o = document.getElementById('lk-overlay');
    o.classList.add('open');
    document.body.style.overflow = 'hidden';
    laddaLeaflet(function () {
      if (!karta) { byggKarta(); ritaForklaring(); }
      // Kartan ritades i en dold ruta — räkna om storleken nu när den syns
      // Zooma till linjerna (inte hela Norden) när rutan har fått sin storlek
      setTimeout(function () {
        karta.invalidateSize();
        var alla = [];
        Object.keys(lager).forEach(function (b) { lager[b].forEach(function (l) { alla.push(l); }); });
        if (alla.length) karta.fitBounds(L.featureGroup(alla).getBounds(), { padding: [18, 18] });
      }, 60);
    });
  }
  function stang() {
    document.getElementById('lk-overlay').classList.remove('open');
    document.body.style.overflow = '';
  }

  function init() {
    var stil = document.createElement('style');
    stil.textContent =
      '.lk-overlay{display:none;position:fixed;inset:0;z-index:1350;background:rgba(8,19,40,.72);' +
        'backdrop-filter:blur(16px) saturate(140%);-webkit-backdrop-filter:blur(16px) saturate(140%);' +
        'align-items:center;justify-content:center;padding:16px;}' +
      '.lk-overlay.open{display:flex;}' +
      '.lk-modal{width:100%;max-width:760px;height:min(640px,calc(100dvh - 32px));display:flex;flex-direction:column;' +
        'background:linear-gradient(160deg,rgba(28,54,110,.78),rgba(10,22,52,.88));border:1px solid rgba(125,211,252,.3);' +
        'border-radius:20px;overflow:hidden;box-shadow:0 20px 60px rgba(0,0,0,.6),0 0 70px rgba(96,165,250,.18);}' +
      '.lk-head{display:flex;align-items:center;gap:10px;padding:12px 16px;border-bottom:1px solid rgba(255,255,255,.08);}' +
      '.lk-titel{flex:1;font-weight:800;color:#fff;font-size:1rem;}' +
      '.lk-titel small{display:block;font-weight:500;font-size:.72rem;color:rgba(191,219,254,.75);margin-top:2px;}' +
      '.lk-stang{background:rgba(255,255,255,.1);border:none;color:#fff;width:30px;height:30px;border-radius:50%;cursor:pointer;}' +
      '#lk-karta{flex:1;min-height:0;background:#0b1526;}' +
      '.lk-forklaring{display:flex;gap:6px;flex-wrap:wrap;padding:10px 14px;border-top:1px solid rgba(255,255,255,.08);}' +
      '.lk-bolag{display:inline-flex;align-items:center;gap:6px;padding:4px 10px;border-radius:14px;cursor:pointer;' +
        'font-size:.72rem;font-weight:700;color:#e0ecff;background:rgba(255,255,255,.06);border:1px solid rgba(147,197,253,.25);}' +
      '.lk-bolag.av{opacity:.4;text-decoration:line-through;}' +
      '.lk-streck{width:16px;height:4px;border-radius:2px;background:var(--f);}' +
      '.lk-pop{display:flex;flex-direction:column;gap:6px;min-width:130px;font-family:inherit;}' +
      '.lk-pop button{padding:6px 10px;border-radius:8px;border:none;cursor:pointer;font-weight:700;' +
        'background:linear-gradient(135deg,#2563eb,#60a5fa);color:#fff;}' +
      '.lk-fel{padding:30px;text-align:center;color:rgba(255,255,255,.7);}' +
      '.lk-oppna{display:flex;align-items:center;justify-content:center;gap:7px;width:100%;margin-top:10px;' +
        'padding:10px;border-radius:12px;cursor:pointer;font-size:.86rem;font-weight:700;color:#dbeafe;' +
        'background:rgba(255,255,255,.07);border:1px solid rgba(147,197,253,.3);' +
        'backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);transition:background .15s;}' +
      '.lk-oppna:hover{background:rgba(59,130,246,.22);color:#fff;}' +
      '#search-form > .lk-oppna{grid-column:1 / -1;}' +
      '@media(max-width:540px){.lk-overlay{padding:0}.lk-modal{height:100dvh;max-width:none;border-radius:0;border:none}}' +
      'body:has(.lk-overlay.open) .tc-fab-wrap{display:none;}';
    document.head.appendChild(stil);

    var ruta = document.createElement('div');
    ruta.innerHTML =
      '<div class="lk-overlay" id="lk-overlay">' +
        '<div class="lk-modal" role="dialog" aria-label="Våra linjer">' +
          '<div class="lk-head"><div class="lk-titel">🗺️ Våra linjer' +
            '<small>Huvudlinjerna — sökningen täcker alla tåg i Trafikverkets data. Tryck på en station.</small></div>' +
            '<button type="button" class="lk-stang" aria-label="Stäng">✕</button></div>' +
          '<div id="lk-karta"></div>' +
          '<div class="lk-forklaring" id="lk-forklaring"></div>' +
        '</div>' +
      '</div>';
    document.body.appendChild(ruta.firstChild);
    document.querySelector('#lk-overlay .lk-stang').onclick = stang;
    document.getElementById('lk-overlay').addEventListener('click', function (e) {
      if (e.target.id === 'lk-overlay') stang();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && document.getElementById('lk-overlay').classList.contains('open')) stang();
    });

    // Knappen under Sök resa
    var sok = document.getElementById('search-btn');
    if (sok) {
      var knapp = document.createElement('button');
      knapp.type = 'button';
      knapp.className = 'lk-oppna';
      knapp.innerHTML = '🗺️ Se våra linjer på kartan';
      knapp.onclick = oppna;
      sok.insertAdjacentElement('afterend', knapp);
    }
    window.mptOppnaLinjekarta = oppna;
  }

  // ── Delas med chattens resekarta (train-chat.js) ──
  window.mptLaddaLeaflet = laddaLeaflet;
  window.mptKartbilder = function (karta) {
    var ESRI = 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/';
    L.tileLayer(ESRI + 'World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 12, minZoom: 3, attribution: '&copy; Esri, OpenStreetMap'
    }).addTo(karta);
    karta.createPane('etiketter');
    karta.getPane('etiketter').style.zIndex = 450;
    karta.getPane('etiketter').style.pointerEvents = 'none';
    L.tileLayer(ESRI + 'World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 12, minZoom: 3, pane: 'etiketter'
    }).addTo(karta);
  };
  /**
   * Spårets mellanstationer mellan två stationer, om någon linje på kartan går genom båda —
   * så att resan följer järnvägen i stället för ett rakt streck. null om ingen linje passar.
   */
  window.mptRuttPunkter = function (fran, till) {
    var bast = null;
    LINJER.forEach(function (l) {
      var a = l.via.indexOf(fran), b = l.via.indexOf(till);
      if (a < 0 || b < 0) return;
      var del = a < b ? l.via.slice(a, b + 1) : l.via.slice(b, a + 1).reverse();
      if (!bast || del.length > bast.length) bast = del;   // fler mellanstationer = närmare spåret
    });
    return bast ? bast.map(function (n) { return ST[n]; }) : null;
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
