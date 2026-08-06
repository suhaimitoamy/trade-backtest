(() => {
  'use strict';
  if (window.__tmlMobileUiPatchLoaded) return;
  window.__tmlMobileUiPatchLoaded = true;

  const byId = id => document.getElementById(id);
  const safe = (name, fn) => {
    try { fn(); }
    catch (error) {
      console.error('[Trading Method Lab]', name, error);
      const status = byId('status');
      if (status) status.textContent = `Komponen ${name} gagal dimuat: ${error.message}`;
    }
  };

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
    .quick-toolbar{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:10px 0}
    .quick-toolbar label{min-width:0}
    .quick-summary{padding:9px 10px;border:1px solid var(--line);border-radius:10px;background:#111722;color:var(--muted);font-size:12px;line-height:1.45;margin-bottom:10px}
    .quick-summary.warn{color:var(--gold);border-color:rgba(240,184,75,.45)}
    .quick-group{margin-top:12px}
    .quick-group-title{margin:0 0 7px;color:var(--gold);font-size:12px;font-weight:900;text-transform:uppercase;letter-spacing:.04em}
    .check-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}
    .check-card{display:flex;align-items:center;gap:9px;min-width:0;padding:10px;border:1px solid var(--line);border-radius:10px;background:rgba(23,31,45,.72);color:var(--text);font-size:12px;font-weight:750;cursor:pointer}
    .check-card.checked{border-color:rgba(240,184,75,.75);background:rgba(240,184,75,.12)}
    .check-card.partial{border-color:rgba(102,167,255,.7);background:rgba(102,167,255,.1)}
    .check-card input{width:20px;height:20px;min-height:20px;flex:0 0 20px;margin:0;accent-color:var(--gold)}
    .check-card span{min-width:0;line-height:1.25}
    .quick-actions{display:flex;gap:7px;margin-top:11px}
    .quick-actions button{flex:1}
    #advancedRuleEditor{border-top:0;margin-top:8px;padding:0}
    #advancedRuleEditor>summary{padding:11px;border:1px solid var(--line);border-radius:10px;background:#111722}
    #advancedRuleEditor[open]>summary{margin-bottom:10px}
    .zero-diagnostic{display:none;margin:0 0 12px;padding:13px;border:1px solid rgba(240,184,75,.45);border-radius:13px;background:rgba(240,184,75,.08)}
    .zero-diagnostic.show{display:block}
    .zero-diagnostic h3{margin:0 0 6px;color:var(--gold);font-size:16px}
    .zero-diagnostic p{margin:4px 0;font-size:12px}
    .zero-diagnostic code{white-space:normal;color:var(--text)}
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
      .fields,.quick-toolbar{grid-template-columns:1fr!important}
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
      { id:'bos', label:'BOS saat ini', buy:'BOS_UP', sell:'BOS_DOWN' },
      { id:'choch', label:'CHOCH saat ini', buy:'CHOCH_UP', sell:'CHOCH_DOWN' },
      { id:'mss', label:'MSS saat ini', buy:'MSS_UP', sell:'MSS_DOWN' },
      { id:'swing', label:'State swing HH-HL / LH-LL', buy:'SWING_HH_HL', sell:'SWING_LH_LL' }
    ]},
    { title: 'ICT dan imbalance', rules: [
      { id:'sweepMss', label:'Liquidity sweep + MSS', buy:'LIQUIDITY_SWEEP_MSS_BUY', sell:'LIQUIDITY_SWEEP_MSS_SELL' },
      { id:'displacement', label:'Displacement', buy:'DISPLACEMENT_BULL', sell:'DISPLACEMENT_BEAR', buyDefaults:{value:1.0,value2:.65}, sellDefaults:{value:1.0,value2:.65} },
      { id:'fvgActive', label:'FVG aktif (konteks)', buy:'FVG_BULL_ACTIVE', sell:'FVG_BEAR_ACTIVE' },
      { id:'fvgRetest', label:'FVG retest (trigger)', buy:'FVG_BULL_RETEST', sell:'FVG_BEAR_RETEST' },
      { id:'orderBlock', label:'Order block retest', buy:'OB_BULL_RETEST', sell:'OB_BEAR_RETEST' },
      { id:'breaker', label:'Breaker retest', buy:'BREAKER_BULL_RETEST', sell:'BREAKER_BEAR_RETEST' },
      { id:'ote', label:'OTE 62-79%', buy:'OTE_BUY_ZONE', sell:'OTE_SELL_ZONE', buyDefaults:{value:.62,value2:.79}, sellDefaults:{value:.62,value2:.79} },
      { id:'pd', label:'Discount / Premium', buy:'DISCOUNT_ZONE', sell:'PREMIUM_ZONE', buyDefaults:{value:.5}, sellDefaults:{value:.5} }
    ]},
    { title: 'Level dan price action', rules: [
      { id:'candle', label:'Candle konfirmasi', buy:'CANDLE_BULL', sell:'CANDLE_BEAR' },
      { id:'breakout', label:'Breakout rolling level', buy:'BREAK_HIGH', sell:'BREAK_LOW' },
      { id:'sweep', label:'Sweep rolling level', buy:'SWEEP_LOW', sell:'SWEEP_HIGH', buyDefaults:{value:.1}, sellDefaults:{value:.1} },
      { id:'rbs', label:'RBS / SBR retest', buy:'RBS_RETEST', sell:'SBR_RETEST', buyDefaults:{value:.2,period:20}, sellDefaults:{value:.2,period:20} },
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
    if (!type || hasType(side, type) || typeof window.addCondition !== 'function') return;
    window.addCondition(side, Object.assign({type}, defaults || {}));
  }
  function removeType(side, type) {
    conditionRows(side).forEach(row => {
      if (row.querySelector('.ctype')?.value === type) row.remove();
    });
  }
  function selectedSide() { return byId('side')?.value || 'both'; }

  function activeRuleCount() {
    return conditionRows('buy').length + conditionRows('sell').length;
  }

  function setLogic(value) {
    if (byId('buyLogic')) byId('buyLogic').value = value;
    if (byId('sellLogic')) byId('sellLogic').value = value;
    if (typeof window.auditNow === 'function') window.auditNow();
  }

  function applyRule(rule, enabled) {
    const side = selectedSide();
    if (enabled) {
      if (side !== 'sell_only') addType('buy', rule.buy, rule.buyDefaults);
      if (side !== 'buy_only') addType('sell', rule.sell, rule.sellDefaults);
      const name = byId('methodName');
      if (name && !name.value.startsWith('Metode checklist')) name.value = 'Metode checklist';
    } else {
      removeType('buy', rule.buy);
      removeType('sell', rule.sell);
    }
    if (typeof window.updateEmaState === 'function') window.updateEmaState();
    if (typeof window.auditNow === 'function') window.auditNow();
    syncChecklist();
  }

  function syncChecklist() {
    const side = selectedSide();
    let activeFamilies = 0;
    flatRules.forEach(rule => {
      const box = byId('quick-' + rule.id);
      if (!box) return;
      const buyOn = hasType('buy', rule.buy);
      const sellOn = hasType('sell', rule.sell);
      box.indeterminate = false;
      if (side === 'buy_only') box.checked = buyOn;
      else if (side === 'sell_only') box.checked = sellOn;
      else {
        box.checked = buyOn && sellOn;
        box.indeterminate = buyOn !== sellOn;
      }
      const card = box.closest('.check-card');
      card?.classList.toggle('checked', box.checked);
      card?.classList.toggle('partial', box.indeterminate);
      if (box.checked || box.indeterminate) activeFamilies++;
    });

    const totalBuy = conditionRows('buy').length;
    const totalSell = conditionRows('sell').length;
    const logic = byId('buyLogic')?.value || 'all';
    const count = byId('quickRuleCount');
    if (count) count.textContent = `${activeFamilies} keluarga rule aktif`;
    const summary = byId('quickRuleSummary');
    if (summary) {
      const mode = logic === 'all' ? 'AND: semua kondisi harus benar pada candle yang sama.' : 'OR: cukup salah satu kondisi benar.';
      summary.textContent = `${mode} BUY ${totalBuy} kondisi, SELL ${totalSell} kondisi.`;
      summary.classList.toggle('warn', logic === 'all' && Math.max(totalBuy,totalSell) >= 4);
      if (logic === 'all' && Math.max(totalBuy,totalSell) >= 4) {
        summary.textContent += ' Filter sangat ketat dan mudah menghasilkan 0 sinyal.';
      }
    }
    const quickLogic = byId('quickLogic');
    if (quickLogic && quickLogic.value !== logic) quickLogic.value = logic;
  }

  function clearRules() {
    const buy = byId('buyConditions');
    const sell = byId('sellConditions');
    if (buy) buy.innerHTML = '';
    if (sell) sell.innerHTML = '';
    const name = byId('methodName');
    if (name) name.value = 'Metode checklist baru';
    if (typeof window.updateEmaState === 'function') window.updateEmaState();
    if (typeof window.auditNow === 'function') window.auditNow();
    syncChecklist();
  }

  function installChecklist() {
    const buyHost = byId('buyConditions');
    const sellHost = byId('sellConditions');
    if (!buyHost || !sellHost || byId('quickRuleBuilder')) return;
    const panel = buyHost.closest('.panel');
    const firstSideHead = panel?.querySelector('.side-head');
    const addSell = byId('addSell');
    if (!panel || !firstSideHead || !addSell) return;

    const quick = document.createElement('div');
    quick.id = 'quickRuleBuilder';
    quick.className = 'quick-rules';
    quick.innerHTML = `
      <div class="quick-rules-head">
        <div><h3>Pilih rule dengan checklist</h3><p>Template mengganti rule lama. Checklist menggabungkan rule yang dicentang.</p></div>
        <span id="quickRuleCount" class="badge">0 keluarga rule aktif</span>
      </div>
      <div class="quick-toolbar">
        <label>Cara gabung rule
          <select id="quickLogic"><option value="all">AND - semua wajib</option><option value="any">OR - salah satu cukup</option></select>
        </label>
        <label>Aksi cepat
          <button id="quickClearRules" type="button" class="secondary small">Mulai metode kosong</button>
        </label>
      </div>
      <div id="quickRuleSummary" class="quick-summary"></div>
      <div id="quickRuleGroups"></div>
      <div class="quick-actions"><button id="quickMirrorRules" type="button" class="secondary small">Sinkronkan BUY ke SELL</button></div>`;
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

    byId('quickClearRules').addEventListener('click', clearRules);
    byId('quickMirrorRules').addEventListener('click', () => {
      if (typeof window.mirrorBuy === 'function') window.mirrorBuy();
      syncChecklist();
    });
    byId('quickLogic').addEventListener('change', event => {
      setLogic(event.target.value);
      syncChecklist();
    });
    byId('side')?.addEventListener('change', syncChecklist);
    buyHost.addEventListener('change', syncChecklist);
    sellHost.addEventListener('change', syncChecklist);
    const observer = new MutationObserver(() => window.requestAnimationFrame(syncChecklist));
    observer.observe(buyHost, {childList:true, subtree:true});
    observer.observe(sellHost, {childList:true, subtree:true});

    const template = byId('templateSelect');
    template?.addEventListener('change', () => {
      try {
        const selected = window.methodPack?.templates?.find(item => item.id === template.value);
        if (selected && typeof window.applyConfig === 'function') {
          window.applyConfig(Object.assign({}, selected.config, {method_name:selected.name}));
          const status = byId('status');
          if (status) status.textContent = `Template ${selected.name} diterapkan dan mengganti rule sebelumnya.`;
          window.setTimeout(syncChecklist, 0);
        }
      } catch (error) { console.error(error); }
    });
    syncChecklist();
  }

  function installDiagnostics() {
    const status = byId('status');
    if (!status || byId('zeroDiagnostic')) return;
    const box = document.createElement('section');
    box.id = 'zeroDiagnostic';
    box.className = 'zero-diagnostic';
    status.insertAdjacentElement('afterend', box);

    const callbacks = window.NativeMethodBuilder || {};
    const originalComplete = callbacks.onComplete;
    window.NativeMethodBuilder = Object.assign({}, callbacks, {
      onComplete: result => {
        if (typeof originalComplete === 'function') originalComplete(result);
        const trades = Number(result?.metrics?.total_trades || 0);
        const audit = result?.audit || {};
        const buy = Number(audit.buy_signals || 0);
        const sell = Number(audit.sell_signals || 0);
        const conflicts = Number(audit.signal_conflicts || 0);
        const evaluated = Number(audit.evaluated_bars || 0);
        const conditions = Array.isArray(audit.condition_stats) ? audit.condition_stats : [];
        if (trades > 0) {
          box.classList.remove('show');
          box.innerHTML = '';
          return;
        }

        const worst = conditions
          .filter(item => Number(item.evaluated || 0) > 0)
          .sort((a,b) => Number(a.pass_rate || 0) - Number(b.pass_rate || 0))
          .slice(0,3);
        let explanation;
        if (buy + sell === 0) {
          explanation = 'Tidak ada candle yang memenuhi seluruh rule. Ini bukan win rate 0%; ini berarti belum ada entry.';
        } else if (conflicts >= buy + sell && conflicts > 0) {
          explanation = 'BUY dan SELL aktif bersamaan sehingga seluruh kandidat dibatalkan sebagai konflik.';
        } else {
          explanation = 'Rule menghasilkan kandidat, tetapi tidak menjadi trade. Ini harus dianggap masalah entry/SL engine dan perlu diperiksa.';
        }
        const bottleneck = worst.length
          ? `<p>Rule paling ketat: ${worst.map(item => `<code>${String(item.condition)} ${Number(item.pass_rate||0).toFixed(2)}%</code>`).join(' · ')}</p>`
          : '';
        box.innerHTML = `<h3>Diagnosis 0 trade</h3><p>${explanation}</p><p>Evaluasi ${evaluated.toLocaleString()} candle · kandidat BUY ${buy.toLocaleString()} · SELL ${sell.toLocaleString()} · konflik ${conflicts.toLocaleString()}.</p>${bottleneck}`;
        box.classList.add('show');
      }
    });

    const runButton = byId('runButton');
    if (runButton && !runButton.dataset.preflightWrapped) {
      runButton.dataset.preflightWrapped = '1';
      const originalRun = runButton.onclick;
      runButton.onclick = event => {
        try {
          const config = typeof window.buildConfig === 'function' ? window.buildConfig() : null;
          const buy = config?.buy_conditions?.length || 0;
          const sell = config?.sell_conditions?.length || 0;
          if (buy + sell === 0) {
            event?.preventDefault?.();
            status.textContent = 'Tidak dapat menjalankan: belum ada rule BUY atau SELL yang aktif.';
            status.className = 'status error';
            return;
          }
          box.classList.remove('show');
          box.innerHTML = '';
        } catch (error) { console.error(error); }
        if (typeof originalRun === 'function') return originalRun.call(runButton, event);
      };
    }
  }

  function installNativeUpdater() {
    const updater = window.AndroidUpdater;
    const status = byId('updateStatus');
    const check = byId('checkUpdate');
    const install = byId('installUpdate');
    const cancel = byId('cancelUpdate');
    if (!updater || !status || !check || !install || !cancel || typeof updater.checkForUpdate !== 'function') return;

    const manifestUrl = 'https://github.com/suhaimitoamy/trade-backtest/releases/download/trading-method-lab-preview/update-manifest.json';
    let cachedManifest = null;
    let nativeCheckStarted = false;
    const setBusy = busy => {
      check.disabled = busy;
      if (busy) install.disabled = true;
    };
    const callbacks = window.NativeUpdater || {};
    window.NativeUpdater = Object.assign({}, callbacks, {
      onManifest: manifest => {
        setBusy(false);
        try {
          const current = JSON.parse(updater.getVersionInfo());
          cachedManifest = manifest;
          const newer = Number(manifest.version_code) > Number(current.version_code);
          status.textContent = newer
            ? `Update ${manifest.version_name} tersedia. ${manifest.notes || ''}`
            : `Aplikasi sudah terbaru (${current.version_name}).`;
          install.disabled = !newer;
        } catch (error) {
          status.textContent = 'Manifest update tidak valid: ' + error.message;
          install.disabled = true;
        }
      },
      onCheckError: payload => {
        setBusy(false);
        status.textContent = 'Cek update native gagal: ' + (payload?.message || 'koneksi gagal');
      },
      onProgress: payload => {
        const percent = Number(payload?.percent);
        status.textContent = `${payload?.message || 'Mengunduh update'}${Number.isFinite(percent) && percent >= 0 ? ` ${percent}%` : ''}`;
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
      nativeCheckStarted = true;
      cachedManifest = null;
      setBusy(true);
      cancel.disabled = true;
      status.textContent = 'Memeriksa update melalui koneksi native…';
      try { updater.checkForUpdate(manifestUrl); }
      catch (error) {
        setBusy(false);
        status.textContent = 'Cek update native gagal: ' + error.message;
      }
    };
    check.onclick = checkNative;
    install.onclick = () => {
      if (!cachedManifest) { status.textContent = 'Cek versi terbaru lebih dulu.'; return; }
      cancel.disabled = false;
      check.disabled = true;
      updater.startAppUpdate(cachedManifest.download_url, cachedManifest.sha256, cachedManifest.signing_cert_sha256);
    };
    cancel.onclick = () => updater.cancelAppUpdate();

    const observer = new MutationObserver(() => {
      if (/Failed to fetch/i.test(status.textContent || '') && !nativeCheckStarted) {
        window.setTimeout(checkNative, 50);
      }
    });
    observer.observe(status, {childList:true,characterData:true,subtree:true});
    window.setTimeout(checkNative, 1800);
  }

  function updateVersionBadge() {
    const badge = document.querySelector('header h1 .badge');
    if (badge) badge.textContent = 'v2.6 rule audit';
  }

  safe('checklist', installChecklist);
  safe('diagnostik', installDiagnostics);
  safe('updater', installNativeUpdater);
  safe('versi', updateVersionBadge);
})();
