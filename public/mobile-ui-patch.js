(() => {
  'use strict';
  if (window.__tmlMobileUiPatchLoaded) return;
  window.__tmlMobileUiPatchLoaded = true;

  const byId = id => document.getElementById(id);
  const style = document.createElement('style');
  style.id = 'tml-mobile-ui-style';
  style.textContent = `
    html,body{max-width:100%;overflow-x:hidden}
    main,.layout,.layout>*,aside,section,.panel,.metrics,.metric,.table-wrap,.update-card{min-width:0;max-width:100%}
    .panel,.metric{overflow:hidden}
    .row,.row>*{min-width:0}
    .status,.note,.audit-box,.metric .value{overflow-wrap:anywhere;word-break:break-word}
    .metric .value{line-height:1.08}
    canvas{display:block;max-width:100%;height:auto!important;aspect-ratio:16/9}
    input,select,button{max-width:100%}
    .quick-rules{margin:12px 0;padding:12px;border:1px solid var(--line);border-radius:13px;background:#0c111b}
    .quick-rules-head{display:flex;align-items:flex-start;justify-content:space-between;gap:10px;margin-bottom:10px}
    .quick-rules-head h3{margin:0 0 3px;font-size:16px}
    .quick-rules-head p{margin:0;font-size:11px}
    .quick-group{margin-top:12px}
    .quick-group-title{margin:0 0 7px;color:var(--gold);font-size:12px;font-weight:900;text-transform:uppercase;letter-spacing:.04em}
    .check-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}
    .check-card{display:flex;align-items:center;gap:9px;min-width:0;padding:10px;border:1px solid var(--line);border-radius:10px;background:rgba(23,31,45,.72);color:var(--text);font-size:12px;font-weight:750;cursor:pointer}
    .check-card:has(input:checked){border-color:rgba(240,184,75,.75);background:rgba(240,184,75,.12)}
    .check-card input{width:20px;height:20px;min-height:20px;flex:0 0 20px;margin:0;accent-color:var(--gold)}
    .check-card span{min-width:0;line-height:1.25}
    .check-card input:indeterminate{outline:2px solid var(--gold);outline-offset:1px}
    .quick-actions{display:flex;gap:7px;margin-top:11px}
    .quick-actions button{flex:1}
    #advancedRuleEditor{border-top:0;margin-top:8px;padding:0}
    #advancedRuleEditor>summary{padding:11px;border:1px solid var(--line);border-radius:10px;background:#111722}
    #advancedRuleEditor[open]>summary{margin-bottom:10px}
    .update-card .row{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
    .update-card .row button{width:100%}
    .update-card .row #cancelUpdate{grid-column:1/-1}
    @media(max-width:760px){
      main{width:calc(100% - 14px)!important;padding-top:10px}
      .layout{display:block!important}
      .panel{padding:14px}
      .fields{gap:8px}
      .metrics{grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:8px}
      .metric{padding:12px}
      .metric .value{font-size:clamp(18px,6vw,27px)!important}
      .condition{grid-template-columns:minmax(0,1fr) 72px 72px 36px!important}
      .condition select,.condition input{min-width:0;padding-left:7px;padding-right:7px}
      .table-wrap{overflow-x:auto!important;-webkit-overflow-scrolling:touch}
    }
    @media(max-width:500px){
      .metrics{grid-template-columns:1fr!important}
      .metric{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:12px}
      .metric .name{margin:0!important;font-size:12px!important}
      .metric .value{font-size:25px!important;text-align:right}
      .fields{grid-template-columns:1fr!important}
      .full{grid-column:auto!important}
      .check-grid{grid-template-columns:1fr}
      .condition{grid-template-columns:minmax(0,1fr) 1fr 1fr 36px!important}
      .quick-rules-head{display:block}
      .quick-actions{display:grid;grid-template-columns:1fr}
      .update-card .row{grid-template-columns:1fr}
      .update-card .row #cancelUpdate{grid-column:auto}
    }
  `;
  document.head.appendChild(style);

  const ruleGroups = [
    { title: 'Market structure', rules: [
      { id:'bos', label:'BOS', buy:'BOS_UP', sell:'BOS_DOWN' },
      { id:'choch', label:'CHOCH', buy:'CHOCH_UP', sell:'CHOCH_DOWN' },
      { id:'mss', label:'MSS', buy:'MSS_UP', sell:'MSS_DOWN' },
      { id:'swing', label:'Swing HH–HL / LH–LL', buy:'SWING_HH_HL', sell:'SWING_LH_LL' }
    ]},
    { title: 'ICT', rules: [
      { id:'sweepMss', label:'Liquidity sweep + MSS', buy:'LIQUIDITY_SWEEP_MSS_BUY', sell:'LIQUIDITY_SWEEP_MSS_SELL' },
      { id:'displacement', label:'Displacement', buy:'DISPLACEMENT_BULL', sell:'DISPLACEMENT_BEAR', buyDefaults:{value:1.5,value2:.6}, sellDefaults:{value:1.5,value2:.6} },
      { id:'fvgActive', label:'FVG aktif', buy:'FVG_BULL_ACTIVE', sell:'FVG_BEAR_ACTIVE' },
      { id:'fvgRetest', label:'FVG retest', buy:'FVG_BULL_RETEST', sell:'FVG_BEAR_RETEST' },
      { id:'orderBlock', label:'Order block retest', buy:'OB_BULL_RETEST', sell:'OB_BEAR_RETEST' },
      { id:'breaker', label:'Breaker retest', buy:'BREAKER_BULL_RETEST', sell:'BREAKER_BEAR_RETEST' },
      { id:'ote', label:'OTE 62–79%', buy:'OTE_BUY_ZONE', sell:'OTE_SELL_ZONE', buyDefaults:{value:.62,value2:.79}, sellDefaults:{value:.62,value2:.79} },
      { id:'pd', label:'Discount / Premium', buy:'DISCOUNT_ZONE', sell:'PREMIUM_ZONE', buyDefaults:{value:.5}, sellDefaults:{value:.5} }
    ]},
    { title: 'Level dan price action', rules: [
      { id:'candle', label:'Candle konfirmasi', buy:'CANDLE_BULL', sell:'CANDLE_BEAR' },
      { id:'breakout', label:'Breakout rolling level', buy:'BREAK_HIGH', sell:'BREAK_LOW' },
      { id:'sweep', label:'Sweep rolling level', buy:'SWEEP_LOW', sell:'SWEEP_HIGH', buyDefaults:{value:.1}, sellDefaults:{value:.1} },
      { id:'rbs', label:'RBS / SBR retest', buy:'RBS_RETEST', sell:'SBR_RETEST', buyDefaults:{value:.15,period:12}, sellDefaults:{value:.15,period:12} },
      { id:'ema', label:'EMA trend (opsional)', buy:'EMA_FAST_ABOVE', sell:'EMA_FAST_BELOW' }
    ]},
    { title: 'Sesi', rules: [
      { id:'asia', label:'Asia killzone', buy:'KILLZONE_ASIA', sell:'KILLZONE_ASIA' },
      { id:'london', label:'London killzone', buy:'KILLZONE_LONDON', sell:'KILLZONE_LONDON' },
      { id:'newYork', label:'New York killzone', buy:'KILLZONE_NEW_YORK', sell:'KILLZONE_NEW_YORK' }
    ]}
  ];
  const flatRules = ruleGroups.flatMap(group => group.rules);

  function conditionRows(side) {
    const host = byId(side + 'Conditions');
    return host ? Array.from(host.children) : [];
  }
  function hasType(side, type) {
    return conditionRows(side).some(row => row.querySelector('.ctype')?.value === type);
  }
  function addType(side, type, defaults) {
    if (!type || hasType(side, type) || typeof addCondition !== 'function') return;
    addCondition(side, Object.assign({type}, defaults || {}));
  }
  function removeType(side, type) {
    conditionRows(side).forEach(row => {
      if (row.querySelector('.ctype')?.value === type) row.remove();
    });
  }
  function selectedSide() {
    return byId('side')?.value || 'both';
  }
  function applyRule(rule, enabled) {
    const side = selectedSide();
    if (enabled) {
      if (side !== 'sell_only') addType('buy', rule.buy, rule.buyDefaults);
      if (side !== 'buy_only') addType('sell', rule.sell, rule.sellDefaults);
    } else {
      removeType('buy', rule.buy);
      removeType('sell', rule.sell);
    }
    if (typeof updateEmaState === 'function') updateEmaState();
    if (typeof auditNow === 'function') auditNow();
    syncChecklist();
  }
  function syncChecklist() {
    const side = selectedSide();
    let active = 0;
    flatRules.forEach(rule => {
      const box = byId('quick-' + rule.id);
      if (!box) return;
      const buyOn = hasType('buy', rule.buy);
      const sellOn = hasType('sell', rule.sell);
      if (side === 'buy_only') {
        box.checked = buyOn; box.indeterminate = false;
      } else if (side === 'sell_only') {
        box.checked = sellOn; box.indeterminate = false;
      } else {
        box.checked = buyOn && sellOn;
        box.indeterminate = buyOn !== sellOn;
      }
      if (box.checked || box.indeterminate) active++;
    });
    const count = byId('quickRuleCount');
    if (count) count.textContent = active + ' checklist aktif';
  }

  function installChecklist() {
    const buyHost = byId('buyConditions');
    const sellHost = byId('sellConditions');
    if (!buyHost || !sellHost || byId('quickRuleBuilder')) return;
    const panel = buyHost.closest('.panel');
    if (!panel) return;
    const firstSideHead = panel.querySelector('.side-head');
    const addSell = byId('addSell');
    if (!firstSideHead || !addSell) return;

    const quick = document.createElement('div');
    quick.id = 'quickRuleBuilder';
    quick.className = 'quick-rules';
    quick.innerHTML = `
      <div class="quick-rules-head">
        <div><h3>Pilih rule dengan checklist</h3><p>Checklist otomatis membuat pasangan BUY dan SELL. Nilai rinci tetap bisa diubah di pengaturan detail.</p></div>
        <span id="quickRuleCount" class="badge">0 checklist aktif</span>
      </div>
      <div id="quickRuleGroups"></div>
      <div class="quick-actions">
        <button id="quickClearRules" type="button" class="secondary small">Kosongkan semua rule</button>
        <button id="quickMirrorRules" type="button" class="secondary small">Sinkronkan BUY → SELL</button>
      </div>`;
    panel.insertBefore(quick, firstSideHead);

    const groupHost = byId('quickRuleGroups');
    ruleGroups.forEach(group => {
      const section = document.createElement('div');
      section.className = 'quick-group';
      section.innerHTML = `<div class="quick-group-title">${group.title}</div><div class="check-grid"></div>`;
      const grid = section.querySelector('.check-grid');
      group.rules.forEach(rule => {
        const label = document.createElement('label');
        label.className = 'check-card';
        label.innerHTML = `<input id="quick-${rule.id}" type="checkbox"><span>${rule.label}</span>`;
        label.querySelector('input').addEventListener('change', event => applyRule(rule, event.target.checked));
        grid.appendChild(label);
      });
      groupHost.appendChild(section);
    });

    const details = document.createElement('details');
    details.id = 'advancedRuleEditor';
    details.innerHTML = '<summary>Pengaturan detail rule dan nilai parameter</summary>';
    panel.insertBefore(details, firstSideHead);
    let node = firstSideHead;
    while (node) {
      const next = node.nextSibling;
      details.appendChild(node);
      if (node === addSell) break;
      node = next;
    }

    byId('quickClearRules').addEventListener('click', () => {
      buyHost.innerHTML = '';
      sellHost.innerHTML = '';
      if (typeof updateEmaState === 'function') updateEmaState();
      if (typeof auditNow === 'function') auditNow();
      syncChecklist();
    });
    byId('quickMirrorRules').addEventListener('click', () => {
      if (typeof mirrorBuy === 'function') mirrorBuy();
      syncChecklist();
    });
    byId('side')?.addEventListener('change', syncChecklist);
    buyHost.addEventListener('change', syncChecklist);
    sellHost.addEventListener('change', syncChecklist);
    const observer = new MutationObserver(() => window.requestAnimationFrame(syncChecklist));
    observer.observe(buyHost, {childList:true, subtree:true});
    observer.observe(sellHost, {childList:true, subtree:true});
    syncChecklist();
  }

  function installNativeUpdater() {
    const updater = window.AndroidUpdater;
    const status = byId('updateStatus');
    const check = byId('checkUpdate');
    const install = byId('installUpdate');
    const cancel = byId('cancelUpdate');
    if (!updater || !status || !check || !install || !cancel) return;

    const manifestUrl = 'https://github.com/suhaimitoamy/trade-backtest/releases/download/trading-method-lab-preview/update-manifest.json';
    let cachedManifest = null;
    const setBusy = busy => {
      check.disabled = busy;
      if (busy) install.disabled = true;
    };
    const formatProgress = payload => {
      const percent = Number(payload?.percent);
      return payload?.message + (Number.isFinite(percent) && percent >= 0 ? ` ${percent}%` : '');
    };
    const callbacks = window.NativeUpdater || {};
    window.NativeUpdater = Object.assign({}, callbacks, {
      onManifest: manifest => {
        setBusy(false);
        try {
          const current = JSON.parse(updater.getVersionInfo());
          cachedManifest = manifest;
          const newer = Number(manifest.version_code) > Number(current.version_code);
          if (newer) {
            status.textContent = `Update ${manifest.version_name} tersedia. ${manifest.notes || ''}`;
            install.disabled = false;
          } else {
            status.textContent = `Aplikasi sudah terbaru (${current.version_name}).`;
            install.disabled = true;
          }
        } catch (error) {
          status.textContent = 'Manifest update tidak valid: ' + error.message;
          install.disabled = true;
        }
      },
      onCheckError: payload => {
        setBusy(false);
        status.textContent = 'Cek update gagal: ' + (payload?.message || 'koneksi native gagal');
      },
      onProgress: payload => {
        status.textContent = formatProgress(payload);
        cancel.disabled = false;
        check.disabled = true;
      },
      onNeedsInstallPermission: () => {
        status.textContent = 'Izinkan instal aplikasi tidak dikenal, kembali ke aplikasi, lalu tekan Instal update lagi.';
        cancel.disabled = true;
        check.disabled = false;
      },
      onInstallerOpened: () => {
        status.textContent = 'Installer Android sudah dibuka.';
        cancel.disabled = true;
        check.disabled = false;
      },
      onError: payload => {
        status.textContent = 'Update gagal: ' + (payload?.message || 'kesalahan tidak diketahui');
        cancel.disabled = true;
        check.disabled = false;
      }
    });

    const checkNative = () => {
      cachedManifest = null;
      setBusy(true);
      cancel.disabled = true;
      status.textContent = 'Memeriksa update melalui koneksi native…';
      try {
        updater.checkForUpdate(manifestUrl);
      } catch (error) {
        setBusy(false);
        status.textContent = 'Cek update gagal: ' + error.message;
      }
    };
    check.onclick = checkNative;
    install.onclick = () => {
      if (!cachedManifest) {
        status.textContent = 'Cek versi terbaru lebih dulu.';
        return;
      }
      cancel.disabled = false;
      check.disabled = true;
      updater.startAppUpdate(cachedManifest.download_url, cachedManifest.sha256, cachedManifest.signing_cert_sha256);
    };
    cancel.onclick = () => updater.cancelAppUpdate();
    window.setTimeout(checkNative, 250);
  }

  function updateVersionBadge() {
    const badge = document.querySelector('header h1 .badge');
    if (badge) badge.textContent = 'v2.5 mobile synced';
  }

  installChecklist();
  installNativeUpdater();
  updateVersionBadge();
})();
