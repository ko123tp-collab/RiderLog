// ===== Rider Log 사냥 화면 =====
// 운행 중일 때만 사냥한다. 화면을 안 보는 동안의 사냥은 다시 열 때 core.js 의 catchUp 으로 한 번에 계산한다.
var RPGAPP = (function () {
  var R = RPG;
  var BASE = (location.protocol.indexOf('http') === 0 ? '' : 'https://ko123tp-collab.github.io') + '/__rpg/';
  var st = null, lv = 1, bpTotal = 0, running = false, inpT = 0, saveT = 0, uiT = 0;
  var root = null, cv, ctx, buf, bx, S = 4, W = 98, H = 75, CW = 390, CH = 300, DPR = 1, GROUND = 62, FOOT = 58;
  var IMG = {}, BGI = {}, BG = null, BGkey = '', raf = 0, last = 0, mounted = false, shopSig = '', shopTab = 'eq';
  var Lv = { mobs: [], nums: [], coins: [], fx: [], flash: 0, banner: null, scroll: 0, skillCd: R.SKILL_CD, bossT: 0, farm: 0, warned: false };
  var hero = { x: 22, anim: 'idle', t: 0, atkN: 0, hitDone: false, dur: 0.5 };
  var FPS = { idle: 8, walk: 10, a1: 14, a2: 14, a3: 16, hurt: 12, death: 10 };

  // ---------- 공통 ----------
  function $(id) { return document.getElementById(id); }
  function fmt(n) {
    n = Math.floor(n);
    if (n < 10000) return n.toLocaleString();
    var u = [['경', 1e16], ['조', 1e12], ['억', 1e8], ['만', 1e4]];
    for (var i = 0; i < u.length; i++) if (n >= u[i][1]) { var v = n / u[i][1]; var t = v >= 100 ? String(Math.floor(v)) : (v >= 10 ? v.toFixed(1) : v.toFixed(2)).replace(/\.?0+$/, ''); return t + u[i][0]; }
    return String(n);
  }
  function reqXp(l) { return 20000 + 8000 * (l - 1); }
  function levelOf(xp) { var l = 1; while (xp >= reqXp(l)) { xp -= reqXp(l); l++; } return l; }
  function heroCh() { return R.CLASSES[R.cls(lv)].ch; }
  function bpLeft() { return Math.max(0, bpTotal - st.bpSpent); }
  function stats() { return R.stats(st, lv); }

  function readInputs(since) {
    var j = JSON.parse(A.rpgInputs(String(since)));
    lv = levelOf(j.xp); bpTotal = j.bp; running = !!j.running;
    return j;
  }
  function save() { if (st) try { A.rpgSave(JSON.stringify(st)); } catch (e) {} }

  // ---------- 그림 ----------
  function loadImages(cb) {
    var left = 0;
    function one(src) { left++; var im = new Image(); im.onload = im.onerror = function () { if (--left === 0) cb(); }; im.src = src; return im; }
    for (var ch in SPR) for (var k in SPR[ch]) IMG[ch + '|' + k] = one(SPR[ch][k].src);
    for (var b in BGS) BGI[b] = BGS[b].map(one);
  }
  function rnd(seed) { var s = seed; return function () { s = (s * 9301 + 49297) % 233280; return s / 233280; }; }
  function px(im, x, y) { var c = document.createElement('canvas'); c.width = im.width; c.height = im.height; var g = c.getContext('2d'); g.drawImage(im, 0, 0); return g.getImageData(x, y, 1, 1).data; }
  function hex(r, g, b) { return '#' + [r, g, b].map(function (v) { return Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0'); }).join(''); }
  function buildBG() {
    var bi = R.where(st.g).biome, key = R.BIOMES[bi].bg + '|' + H;
    if (key === BGkey) return; BGkey = key;
    var L = BGI[R.BIOMES[bi].bg]; if (!L) return;
    var top = px(L[0], 2, 1), base = null;
    for (var i = L.length - 1; i >= 0 && !base; i--) for (var y = L[i].height - 1; y > L[i].height - 6 && !base; y--) for (var x = 0; x < L[i].width; x += 7) { var p = px(L[i], x, y); if (p[3] > 200) { base = p; break; } }
    base = base || top;
    var TW = 320, gr = document.createElement('canvas'); gr.width = TW; gr.height = 40;
    var g = gr.getContext('2d'), r = rnd(bi * 17 + 11);
    var d = function (k) { return hex(base[0] * k, base[1] * k, base[2] * k); };
    g.fillStyle = d(0.62); g.fillRect(0, 0, TW, 40);
    g.fillStyle = d(0.9); g.fillRect(0, 0, TW, 2);
    g.fillStyle = d(1.15); g.fillRect(0, 0, TW, 1);
    for (var x2 = 0; x2 < TW; x2++) {
      if (r() < 0.3) { g.fillStyle = d(0.9); g.fillRect(x2, 2, 1, 1); }
      if (r() < 0.07) { g.fillStyle = d(0.5); g.fillRect(x2, 4 + Math.floor(r() * 12), 2 + Math.floor(r() * 2), 1); }
      if (r() < 0.04) { g.fillStyle = d(0.75); g.fillRect(x2, 6 + Math.floor(r() * 10), 1, 1); }
    }
    BG = { L: L, gr: gr, sky: hex(top[0], top[1], top[2]) };
  }
  function resize() {
    if (!cv) return;
    CW = cv.parentElement.clientWidth || 360; CH = 260;
    S = Math.max(3, Math.round(CW / 90)); W = Math.ceil(CW / S); H = Math.ceil(CH / S); GROUND = H - 12;
    DPR = Math.min(3, window.devicePixelRatio || 1);
    cv.width = CW * DPR; cv.height = CH * DPR; cv.style.width = CW + 'px'; cv.style.height = CH + 'px';
    buf.width = W; buf.height = H; BGkey = ''; buildBG();
  }

  // ---------- 소리 ----------
  var SND = { ctx: null, buf: {}, on: false, last: {}, bgm: null, bgmName: null, sfxGain: null, bgmGain: null, loading: false };
  var SFX = ['swing1', 'swing2', 'draw', 'hit1', 'hit2', 'crit', 'boom', 'hurt', 'coin', 'coins', 'lvup', 'job', 'boss', 'portal', 'bgm_hunt', 'bgm_boss', 'bgm_town'];
  function sndInit() {
    if (SND.ctx || SND.loading) return Promise.resolve();
    var C = window.AudioContext || window.webkitAudioContext; if (!C) return Promise.resolve();
    SND.loading = true;
    SND.ctx = new C();
    SND.sfxGain = SND.ctx.createGain(); SND.sfxGain.gain.value = 0.55; SND.sfxGain.connect(SND.ctx.destination);
    SND.bgmGain = SND.ctx.createGain(); SND.bgmGain.gain.value = 0.3; SND.bgmGain.connect(SND.ctx.destination);
    return Promise.all(SFX.map(function (k) {
      return fetch(BASE + 'aud/' + k + '.ogg').then(function (r) { return r.arrayBuffer(); }).then(function (a) { return SND.ctx.decodeAudioData(a); })
        .then(function (b) { SND.buf[k] = b; }).catch(function () {});
    })).then(function () { SND.loading = false; });
  }
  function sfx(name, vol, rate) {
    if (!SND.on || !SND.ctx || !SND.buf[name]) return;
    var now = SND.ctx.currentTime;
    if (SND.last[name] && now - SND.last[name] < 0.06) return;
    SND.last[name] = now;
    var s = SND.ctx.createBufferSource(), g = SND.ctx.createGain();
    s.buffer = SND.buf[name]; s.playbackRate.value = rate || 1; g.gain.value = vol == null ? 1 : vol;
    s.connect(g); g.connect(SND.sfxGain); s.start();
  }
  function bgm(name) {
    if (!SND.ctx) return;
    if (!SND.on) name = null;
    if (SND.bgmName === name) return;
    SND.bgmName = name;
    var t = SND.ctx.currentTime;
    if (SND.bgm) { var old = SND.bgm; old.g.gain.setTargetAtTime(0, t, 0.25); setTimeout(function () { try { old.s.stop(); } catch (e) {} }, 1500); SND.bgm = null; }
    if (!name || !SND.buf['bgm_' + name]) return;
    var s = SND.ctx.createBufferSource(), g = SND.ctx.createGain();
    s.buffer = SND.buf['bgm_' + name]; s.loop = true; g.gain.value = 0; g.gain.setTargetAtTime(1, t, 0.3);
    s.connect(g); g.connect(SND.bgmGain); s.start(); SND.bgm = { s: s, g: g };
  }
  function wantBgm() { return !running ? 'town' : Lv.mobs.some(function (m) { return m.boss && !m.dead; }) ? 'boss' : 'hunt'; }
  function setSound(on) {
    try { localStorage.setItem('rpgSound', on ? '1' : '0'); } catch (e) {}
    SND.on = on;
    var b = $('rpgSnd'); if (b) b.textContent = on ? '소리 끄기' : '소리 켜기';
    if (!on) { bgm(null); return; }
    sndInit().then(function () { if (SND.ctx.state === 'suspended') SND.ctx.resume(); SND.bgmName = undefined; bgm(wantBgm()); });
  }

  // ---------- 전투 ----------
  function ex(m) { return m.x - (m.boss ? 10 : 0); }
  function spawn() {
    var b = R.BIOMES[R.where(st.g).biome];
    if (R.isBoss(st.g) && Lv.farm === 0) {
      var hp = R.bossHp(st.g);
      Lv.mobs.push({ ch: b.boss, x: W + 20, boss: true, hp: hp, max: hp, anim: 'walk', t: 0, atkCd: 1.5, hitDone: false, dead: false, kb: 0 });
      Lv.bossT = R.BOSS_LIMIT + 2;
      Lv.banner = { text: 'BOSS · ' + b.bossName, t: 2.2 }; sfx('boss', 0.6); bgm('boss');
      return;
    }
    var g = Lv.farm > 0 ? st.g - 1 : st.g, n = 1 + Math.floor(Math.random() * 3);
    for (var i = 0; i < n; i++) {
      var hp2 = R.hp(g);
      Lv.mobs.push({ ch: b.mobs[Math.floor(Math.random() * b.mobs.length)], x: W + 8 + i * 15, boss: false, hp: hp2, max: hp2, g: g, anim: 'walk', t: 0, atkCd: 1 + Math.random() * 1.5, hitDone: false, dead: false, kb: 0 });
    }
  }
  function dmgNum(x, y, v, crit, enemy, gold) { Lv.nums.push({ x: x, y: y, v: v, crit: crit, enemy: enemy, gold: gold, t: 0 }); }
  function stageChanged(prevBiome) {
    var w = R.where(st.g);
    if (w.biome !== prevBiome) { buildBG(); Lv.banner = { text: (w.loop > 1 ? w.loop + '회차 · ' : '') + '새 사냥터 · ' + R.BIOMES[w.biome].name, t: 2.6 }; sfx('portal', 0.6); }
    else Lv.banner = { text: 'STAGE ' + w.label, t: 1.2, small: true };
    shopSig = '';
  }
  function onKill(m) {
    var s = st, prevBiome = R.where(s.g).biome;
    var gold = m.boss ? R.gold(s.g, s) * 30 : R.gold(m.g, s);
    s.gold += gold; s.total.gold += gold; s.total.kills++;
    sfx(m.boss ? 'coins' : 'coin', 0.5);
    dmgNum(m.x, GROUND - 34, '+' + fmt(gold) + 'G', false, false, true);
    for (var i = 0; i < (m.boss ? 14 : 4); i++) Lv.coins.push({ x: m.x, y: GROUND - 10, vx: (Math.random() - 0.3) * 30, vy: -40 - Math.random() * 40, t: 0 });
    if (m.boss) {
      s.total.bosses++; s.g++; s.kills = 0; s.best = Math.max(s.best, s.g); Lv.flash = 0.3;
      bgm('hunt'); stageChanged(prevBiome); Lv.banner = { text: '보스 처치!', t: 1.6 };
      var nb = R.where(s.g).biome; if (nb !== prevBiome) setTimeout(function () { stageChanged(prevBiome); }, 1700);
      return;
    }
    if (Lv.farm > 0) { Lv.farm--; if (Lv.farm === 0) Lv.banner = { text: '보스 재도전!', t: 1.4, small: true }; return; }
    s.kills++;
    if (s.kills >= R.KILLS_PER_STAGE) {
      s.kills = 0;
      if (R.canAdvance(s.g, stats().dps)) { s.g++; s.best = Math.max(s.best, s.g); Lv.warned = false; stageChanged(prevBiome); }
      else if (!Lv.warned) { Lv.warned = true; Lv.banner = { text: '전투력 부족 · 강화하면 다음 스테이지로', t: 2.2, small: true }; }
    }
  }
  function update(dt) {
    hero.t += dt;
    var ch = heroCh();
    if (!running) {
      Lv.mobs = []; Lv.bossT = 0;
      if (hero.anim !== 'idle') { hero.anim = 'idle'; hero.t = 0; }
      tickFx(dt); return;
    }
    var s = stats();
    var alive = Lv.mobs.filter(function (m) { return !m.dead; }).sort(function (a, b) { return ex(a) - ex(b); });
    var front = alive[0], range = hero.x + 14;
    var walking = !front || ex(front) > range + 2;
    if (walking) {
      Lv.scroll += dt * 16;
      Lv.mobs.forEach(function (m) { m.x -= dt * 16; });
      if (hero.anim !== 'walk' && hero.anim.charAt(0) !== 'a') { hero.anim = 'walk'; hero.t = 0; }
    }
    if (!Lv.mobs.length) spawn();
    // 보스 제한 시간
    var boss = alive.filter(function (m) { return m.boss; })[0];
    if (boss) {
      Lv.bossT -= dt;
      if (Lv.bossT <= 0) {
        boss.dead = true; boss.anim = 'idle'; boss.t = 99; boss.fled = true;
        st.total.fails++; Lv.farm = R.KILLS_PER_STAGE;
        Lv.banner = { text: '보스 도전 실패 · 강화하고 다시 도전해요', t: 2.6, small: true }; bgm('hunt');
      }
    }
    // 영웅 공격
    if (hero.anim.charAt(0) === 'a') {
      var n = SPR[ch][hero.anim].n, f = Math.floor(hero.t / hero.dur * n);
      if (!hero.hitDone && f >= Math.floor(n * 0.5)) {
        hero.hitDone = true;
        var skill = hero.anim === 'a3';
        var targets = Lv.mobs.filter(function (m) { return !m.dead && ex(m) <= range + (skill ? 26 : 3); });
        (skill ? targets : targets.slice(0, 1)).forEach(function (m) {
          var crit = Math.random() < s.critC;
          var d = s.atk * (crit ? s.critD : 1) * (skill ? s.skillMult : 1) * (0.92 + Math.random() * 0.16);
          m.hp -= d; m.kb = 2;
          sfx(crit || skill ? 'crit' : (Math.random() < 0.5 ? 'hit1' : 'hit2'), crit || skill ? 0.8 : 0.55, 0.9 + Math.random() * 0.2);
          dmgNum(m.x, GROUND - (m.boss ? 30 : 24), fmt(d) + (crit ? '!' : ''), crit || skill, false);
          if (m.hp <= 0) { m.dead = true; m.anim = 'death'; m.t = 0; onKill(m); }
          else if (m.anim.charAt(0) !== 'a') { m.anim = 'hurt'; m.t = 0; }
        });
        if (skill) { sfx('boom', 0.7); Lv.flash = 0.22; Lv.fx.push({ x: hero.x + 16, t: 0 }); }
      }
      if (hero.t >= hero.dur) { hero.anim = 'idle'; hero.t = 0; }
    } else if (front && ex(front) <= range + 2) {
      Lv.skillCd -= dt;
      if (Lv.skillCd <= 0) { hero.anim = 'a3'; Lv.skillCd = R.SKILL_CD; hero.dur = 0.75; Lv.banner = Lv.banner || { text: '회오리 베기!', t: 0.8, small: true }; sfx('draw', 0.9); }
      else { hero.atkN++; hero.anim = hero.atkN % 3 === 0 ? 'a2' : 'a1'; hero.dur = s.interval; sfx(hero.atkN % 2 ? 'swing1' : 'swing2', 0.4, 0.95 + Math.random() * 0.15); }
      hero.t = 0; hero.hitDone = false;
    } else if (hero.anim === 'hurt' && hero.t * FPS.hurt >= SPR[ch].hurt.n) { hero.anim = walking ? 'walk' : 'idle'; hero.t = 0; }
    if (hero.anim === 'walk' && !walking) { hero.anim = 'idle'; hero.t = 0; }
    if (front && ex(front) <= range + 2) Lv.skillCd = Math.max(Lv.skillCd, -1);
    // 몬스터
    Lv.mobs.forEach(function (m) {
      m.t += dt;
      if (m.kb > 0) { m.x += m.kb * dt * 20; m.kb = Math.max(0, m.kb - dt * 10); }
      if (m.dead) return;
      var others = Lv.mobs.filter(function (o) { return o !== m && !o.dead && o.x < m.x; });
      var blockX = others.length ? Math.max.apply(null, others.map(function (o) { return o.x; })) + 15 : range;
      if (ex(m) > blockX) { m.x -= dt * 10; if (m.anim !== 'walk' && m.anim.charAt(0) !== 'a' && m.anim !== 'hurt') { m.anim = 'walk'; m.t = 0; } }
      else if (m.anim === 'walk') { m.anim = 'idle'; m.t = 0; }
      var n2 = SPR[m.ch][m.anim].n, f2 = Math.floor(m.t * FPS[m.anim]);
      if (m.anim.charAt(0) === 'a') {
        if (!m.hitDone && f2 >= Math.floor(n2 * 0.55)) {
          m.hitDone = true;
          if (ex(m) <= range + 3) { dmgNum(hero.x, GROUND - 26, fmt(R.hp(st.g) * 0.05 * (m.boss ? 3 : 1)), false, true); sfx('hurt', 0.4); if (hero.anim.charAt(0) !== 'a') { hero.anim = 'hurt'; hero.t = 0; } }
        }
        if (f2 >= n2) { m.anim = 'idle'; m.t = 0; }
      } else if (m.anim === 'hurt' && f2 >= n2) { m.anim = 'idle'; m.t = 0; }
      if (ex(m) <= range + 3 && m.anim.charAt(0) !== 'a' && m.anim !== 'hurt') {
        m.atkCd -= dt;
        if (m.atkCd <= 0) { m.anim = m.boss && Math.random() < 0.4 ? 'a2' : 'a1'; m.t = 0; m.hitDone = false; m.atkCd = m.boss ? 1.8 : 2.4 + Math.random(); }
      }
    });
    Lv.mobs = Lv.mobs.filter(function (m) { return !(m.dead && (m.fled ? true : m.t * FPS.death > SPR[m.ch].death.n + 6)); });
    tickFx(dt);
  }
  function tickFx(dt) {
    Lv.nums.forEach(function (n) { n.t += dt; }); Lv.nums = Lv.nums.filter(function (n) { return n.t < 1.1; });
    Lv.coins.forEach(function (c) { c.t += dt; c.vy += dt * 120; c.y += c.vy * dt; c.x += c.vx * dt; if (c.y > GROUND - 2) { c.y = GROUND - 2; c.vy *= -0.4; c.vx *= 0.6; } });
    Lv.coins = Lv.coins.filter(function (c) { return c.t < 1.3; });
    Lv.fx.forEach(function (e) { e.t += dt; }); Lv.fx = Lv.fx.filter(function (e) { return e.t < 0.35; });
    if (Lv.flash > 0) Lv.flash -= dt;
    if (Lv.banner) { Lv.banner.t -= dt; if (Lv.banner.t <= 0) Lv.banner = null; }
  }

  // ---------- 그리기 ----------
  function drawSprite(ch, anim, t, x, flip, scale, loop, dur) {
    var im = IMG[ch + '|' + anim]; if (!im || !SPR[ch][anim]) return;
    var n = SPR[ch][anim].n;
    var f = dur ? Math.floor(t / dur * n) : Math.floor(t * FPS[anim]);
    f = loop ? f % n : Math.min(f, n - 1);
    var sc = scale || 1, dw = 100 * sc, dx = Math.round(x - 50 * sc), dy = Math.round(GROUND - FOOT * sc);
    bx.save();
    if (flip) { bx.translate(dx + dw, dy); bx.scale(-1, 1); bx.drawImage(im, f * 100, 0, 100, 100, 0, 0, dw, dw); }
    else bx.drawImage(im, f * 100, 0, 100, 100, dx, dy, dw, dw);
    bx.restore();
  }
  function tile(c, off, y) { var w = c.width, o = ((off % w) + w) % w; for (var x = -o; x < W; x += w) bx.drawImage(c, Math.round(x), y); }
  function render() {
    bx.imageSmoothingEnabled = false;
    if (BG) {
      bx.fillStyle = BG.sky; bx.fillRect(0, 0, W, H);
      var by = GROUND + 2 - 64, sp = [0.05, 0.18, 0.38, 0.62];
      BG.L.forEach(function (im, i) { tile(im, Lv.scroll * sp[i], by); });
      tile(BG.gr, Lv.scroll, GROUND);
    }
    Lv.mobs.slice().sort(function (a, b) { return b.x - a.x; }).forEach(function (m) {
      var loop = m.anim === 'walk' || m.anim === 'idle';
      if (m.dead && m.t * FPS.death > SPR[m.ch].death.n) bx.globalAlpha = Math.max(0, 1 - (m.t * FPS.death - SPR[m.ch].death.n) / 6);
      drawSprite(m.ch, m.anim, m.t, m.x, true, m.boss ? 2 : 1, loop);
      bx.globalAlpha = 1;
      if (!m.dead && !m.boss) { var w = 12; bx.fillStyle = '#1B1F2A'; bx.fillRect(Math.round(m.x - w / 2) - 1, GROUND - 25, w + 2, 3); bx.fillStyle = '#E5484D'; bx.fillRect(Math.round(m.x - w / 2), GROUND - 24, Math.max(0, Math.round(w * m.hp / m.max)), 1); }
    });
    var ch = heroCh(), atk = hero.anim.charAt(0) === 'a';
    drawSprite(ch, hero.anim, hero.t, hero.x, false, 1, hero.anim === 'walk' || hero.anim === 'idle', atk ? hero.dur : 0);
    Lv.coins.forEach(function (c) { bx.fillStyle = '#F7C948'; bx.fillRect(Math.round(c.x), Math.round(c.y), 2, 2); bx.fillStyle = '#FFF3B0'; bx.fillRect(Math.round(c.x), Math.round(c.y), 1, 1); });
    Lv.fx.forEach(function (e) {
      bx.globalAlpha = 1 - e.t / 0.35; bx.fillStyle = '#FFFFFF';
      for (var i = 0; i < 24; i++) { var a = -1.2 + i * 0.1 + e.t * 6; bx.fillRect(Math.round(e.x + Math.cos(a) * (12 + e.t * 20)), Math.round(GROUND - 14 + Math.sin(a) * (10 + e.t * 14)), 2, 1); }
      bx.globalAlpha = 1;
    });
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0); ctx.imageSmoothingEnabled = false;
    ctx.drawImage(buf, 0, 0, W * S, H * S);
    if (Lv.flash > 0) { ctx.fillStyle = 'rgba(255,255,255,' + (Lv.flash * 1.6).toFixed(2) + ')'; ctx.fillRect(0, 0, CW, CH); }
    var boss = Lv.mobs.filter(function (m) { return m.boss && !m.dead; })[0];
    if (boss) {
      ctx.fillStyle = 'rgba(11,14,21,.85)'; ctx.fillRect(10, 10, CW - 20, 32);
      ctx.font = '700 12px "Noto Sans KR",sans-serif'; ctx.textAlign = 'left'; ctx.fillStyle = '#FF9C9C'; ctx.fillText(R.BIOMES[R.where(st.g).biome].bossName, 18, 25);
      ctx.textAlign = 'right'; ctx.fillStyle = Lv.bossT < 8 ? '#FF6B6B' : '#C9D1DE'; ctx.fillText(Math.max(0, Math.ceil(Lv.bossT)) + '초', CW - 18, 25);
      ctx.fillStyle = '#3A1E22'; ctx.fillRect(18, 31, CW - 36, 5); ctx.fillStyle = '#E5484D'; ctx.fillRect(18, 31, (CW - 36) * Math.max(0, boss.hp) / boss.max, 5);
    }
    ctx.textAlign = 'center';
    Lv.nums.forEach(function (n) {
      var y = (n.y - n.t * 14) * S, x = n.x * S;
      ctx.globalAlpha = Math.min(1, (1.1 - n.t) * 3);
      ctx.font = (n.crit ? '13px' : n.gold ? '9px' : '10px') + ' "Press Start 2P",monospace';
      ctx.lineWidth = 4; ctx.strokeStyle = '#1B1F2A';
      var t2 = n.enemy ? '-' + n.v : String(n.v);
      ctx.strokeText(t2, x, y);
      ctx.fillStyle = n.enemy ? '#FF6B6B' : n.gold ? '#F7C948' : n.crit ? '#FFC94A' : '#FFFFFF';
      ctx.fillText(t2, x, y);
    });
    ctx.globalAlpha = 1;
    if (Lv.banner) {
      ctx.font = '900 ' + (Lv.banner.small ? 15 : 21) + 'px "Noto Sans KR",sans-serif';
      ctx.lineWidth = 6; ctx.strokeStyle = '#1B1F2A';
      var by2 = Lv.banner.small ? 78 : 104;
      ctx.strokeText(Lv.banner.text, CW / 2, by2);
      ctx.fillStyle = Lv.banner.small ? '#BDE3FF' : '#FFC27A'; ctx.fillText(Lv.banner.text, CW / 2, by2);
    }
    ctx.textAlign = 'left';
  }

  // ---------- 화면 (HTML) ----------
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function ui() {
    var s = stats(), w = R.where(st.g), c = R.CLASSES[R.cls(lv)];
    $('rpgLv').textContent = 'Lv ' + lv; $('rpgCls').textContent = c.name;
    $('rpgPow').textContent = fmt(s.power); $('rpgGold').textContent = fmt(st.gold); $('rpgBp').textContent = fmt(bpLeft());
    $('rpgStage').textContent = (w.loop > 1 ? w.loop + '회차 ' : '') + w.label; $('rpgBiome').textContent = R.BIOMES[w.biome].name;
    var need = R.needPower(st.g), ok = s.power * 1 >= need;
    $('rpgNeed').innerHTML = (R.isBoss(st.g) ? '보스를 ' + R.BOSS_LIMIT + '초 안에 잡으려면' : '다음 스테이지로 가려면') + ' 전투력 <b>' + fmt(need) + '</b> 필요' +
      (ok ? ' <span style="color:var(--ok)">· 충분해요</span>' : ' <span style="color:var(--acc)">· ' + fmt(need - s.power) + ' 부족</span>');
    $('rpgNeedBar').style.width = Math.min(100, s.power / need * 100).toFixed(1) + '%';
    $('rpgNeedBar').style.background = ok ? 'var(--ok)' : 'var(--acc)';
    var pips = '';
    var killsShown = R.isBoss(st.g) ? (Lv.farm > 0 ? R.KILLS_PER_STAGE - Lv.farm : R.KILLS_PER_STAGE) : st.kills;
    $('rpgKills').textContent = R.isBoss(st.g) ? (Lv.farm > 0 ? '보스 재도전까지 ' + Lv.farm + '마리' : '보스전') : '처치 ' + killsShown + ' / ' + R.KILLS_PER_STAGE;
    for (var i = 0; i < 10; i++) pips += '<i class="' + (i + 1 < w.stage ? 'on' : i + 1 === w.stage ? 'cur' : '') + (i === 9 ? ' boss' : '') + '"></i>';
    $('rpgPips').innerHTML = pips;
    $('rpgIdle').style.display = running ? 'none' : 'flex';
    renderShop();
  }
  function row(kind, k, def, lvNow, cost, cur, nxt, can, maxed) {
    return '<div class="rrow"><div style="flex:1;min-width:0"><div class="rname">' + esc(def.name) + ' <span>+' + lvNow + '</span></div>' +
      '<div class="rdesc">' + esc(def.desc) + ' ' + cur + (maxed ? ' (최대)' : ' → <b>' + nxt + '</b>') + '</div></div>' +
      (maxed ? '<button class="rbtn" disabled>최대</button>' : '<button class="rbtn' + (can ? ' can' : '') + '" data-up="' + kind + ':' + k + '"' + (can ? '' : ' disabled') + '>' +
        '<span>' + (kind === 'e' ? '골드' : '포인트') + '</span>' + fmt(cost) + '</button>') + '</div>';
  }
  function pct(v) { return (v * 100).toFixed(v < 0.1 ? 1 : 0) + '%'; }
  function renderShop() {
    var el = $('rpgShop'); if (!el) return;
    var sig = shopTab + '|' + st.gold.toFixed(0).length + '|' + JSON.stringify(st.eq) + JSON.stringify(st.sk) + '|' + bpLeft() + '|' + lv;
    // 강화 가능 여부가 바뀔 때만 다시 그림 (버튼이 계속 바뀌면 누르기 어려움)
    var aff = Object.keys(R.EQUIP).map(function (k) { return st.gold >= R.eqCost(k, st.eq[k]) ? 1 : 0; }).join('') + Object.keys(R.SKILLS).map(function (k) { return bpLeft() >= R.skCost(k, st.sk[k]) ? 1 : 0; }).join('');
    sig = shopTab + '|' + aff + '|' + JSON.stringify(st.eq) + JSON.stringify(st.sk) + '|' + lv;
    if (sig === shopSig) return; shopSig = sig;
    var s = stats(), h = '';
    if (shopTab === 'eq') {
      var E = R.EQUIP, P = R.P;
      h += row('e', 'weapon', E.weapon, st.eq.weapon, R.eqCost('weapon', st.eq.weapon), '+' + pct(Math.pow(P.wPer, st.eq.weapon) - 1), '+' + pct(Math.pow(P.wPer, st.eq.weapon + 1) - 1), st.gold >= R.eqCost('weapon', st.eq.weapon));
      h += row('e', 'glove', E.glove, st.eq.glove, R.eqCost('glove', st.eq.glove), pct(s.critD), pct(s.critD + E.glove.per), st.gold >= R.eqCost('glove', st.eq.glove));
      h += row('e', 'ring', E.ring, st.eq.ring, R.eqCost('ring', st.eq.ring), '+' + pct(E.ring.per * st.eq.ring), '+' + pct(E.ring.per * (st.eq.ring + 1)), st.gold >= R.eqCost('ring', st.eq.ring));
    } else {
      var K = R.SKILLS, b = bpLeft();
      h += row('s', 'whirl', K.whirl, st.sk.whirl, R.skCost('whirl', st.sk.whirl), pct(s.skillMult), pct(s.skillMult + 0.4), b >= R.skCost('whirl', st.sk.whirl));
      h += row('s', 'focus', K.focus, st.sk.focus, R.skCost('focus', st.sk.focus), '+' + pct(Math.pow(R.P.fPer, st.sk.focus) - 1), '+' + pct(Math.pow(R.P.fPer, st.sk.focus + 1) - 1), b >= R.skCost('focus', st.sk.focus));
      h += row('s', 'crit', K.crit, st.sk.crit, R.skCost('crit', st.sk.crit), pct(s.critC), pct(Math.min(0.6, s.critC + 0.0125)), b >= R.skCost('crit', st.sk.crit), st.sk.crit >= K.crit.max);
      h += row('s', 'haste', K.haste, st.sk.haste, R.skCost('haste', st.sk.haste), '+' + pct(0.04 * st.sk.haste), '+' + pct(0.04 * (st.sk.haste + 1)), b >= R.skCost('haste', st.sk.haste), st.sk.haste >= K.haste.max);
    }
    el.innerHTML = h;
    var t1 = $('rpgTabEq'), t2 = $('rpgTabSk');
    if (t1) { t1.classList.toggle('on', shopTab === 'eq'); t2.classList.toggle('on', shopTab === 'sk'); }
    $('rpgStats').innerHTML = '공격력 <b>' + fmt(s.atk) + '</b> · 치명타 <b>' + pct(s.critC) + '</b> · 치명타 피해 <b>' + pct(s.critD) + '</b> · 공격 간격 <b>' + s.interval.toFixed(2) + '초</b>';
  }
  function upgrade(spec) {
    var p = spec.split(':'), ok = p[0] === 'e' ? R.eqUp(st, p[1]) : R.skUp(st, p[1], bpTotal);
    if (ok) { sfx('lvup', 0.35, 1.4); shopSig = ''; save(); ui(); }
  }
  function autoUp() {
    var n = R.autoUp(st, lv, bpTotal);
    if (n) { sfx('lvup', 0.6); Lv.banner = { text: '강화 ' + n + '번 완료!', t: 1.4, small: true }; shopSig = ''; save(); ui(); }
    else toastMsg('지금은 강화할 수 있는 게 없어요');
  }
  function toastMsg(t) { Lv.banner = { text: t, t: 1.6, small: true }; }
  function modal(html) {
    var m = document.createElement('div'); m.className = 'modal';
    m.innerHTML = '<div class="box rpgbox">' + html + '<button class="btn pri" style="margin-top:16px;width:100%">확인</button></div>';
    m.onclick = function (e) { if (e.target === m || e.target.tagName === 'BUTTON') m.remove(); };
    document.body.appendChild(m);
  }
  function report(rep) {
    if (rep.sec < 60) return;
    var w0 = R.where(rep.from), w1 = R.where(rep.to), mins = Math.round(rep.sec / 60);
    var h = '<div style="font-weight:800;letter-spacing:2px;opacity:.75;font-size:12px">운행 중 자동 사냥 결과</div>' +
      '<div style="font-size:24px;font-weight:900;margin:6px 0 14px">' + (mins >= 60 ? Math.floor(mins / 60) + '시간 ' + (mins % 60) + '분' : mins + '분') + ' 사냥</div>' +
      '<div class="rgrid"><div>처치<b>' + fmt(rep.kills) + '</b></div><div>골드<b style="color:#F7C948">+' + fmt(rep.gold) + '</b></div>' +
      '<div>스테이지<b>' + w0.label + ' → ' + w1.label + '</b></div><div>보스<b>' + rep.bosses + '승' + (rep.fails ? ' ' + rep.fails + '패' : '') + '</b></div></div>';
    if (rep.ups) h += '<div style="margin-top:12px;font-size:13px;opacity:.85">자동 강화 ' + rep.ups + '번 했어요.</div>';
    else if (rep.stuck || rep.fails) h += '<div style="margin-top:12px;font-size:13px;opacity:.85">전투력이 부족해서 멈춰 있었어요. 골드와 배달 포인트로 강화해 보세요.</div>';
    modal(h);
  }

  // ---------- 진행 ----------
  function loop(now) {
    if (!mounted) return;
    var dt = Math.min(0.05, (now - last) / 1000); last = now;
    var t = Date.now();
    if (t - inpT > 15000) {
      inpT = t;
      var wasRun = running, lvOld = lv, c0 = R.cls(lv);
      try { readInputs(t); } catch (e) {}
      if (!wasRun && running) { Lv.banner = { text: '운행 시작 · 자동 사냥 시작!', t: 1.8 }; }
      if (lv > lvOld) { sfx(R.cls(lv) !== c0 ? 'job' : 'lvup', 0.7); Lv.banner = { text: R.cls(lv) !== c0 ? '전직! ' + R.CLASSES[R.cls(lv)].name : 'LEVEL UP! Lv ' + lv, t: 2.2 }; Lv.flash = 0.3; st.cls = R.cls(lv); }
      shopSig = '';
    }
    update(dt);
    st.simTs = t;
    render();
    if (SND.on && SND.ctx) bgm(wantBgm());
    uiT += dt; if (uiT > 0.25) { uiT = 0; ui(); }
    saveT += dt; if (saveT > 10) { saveT = 0; if (st.auto && running && R.autoUp(st, lv, bpTotal)) shopSig = ''; save(); }
    raf = requestAnimationFrame(loop);
  }
  function onVis() {
    if (!mounted) return;
    if (document.hidden) { save(); if (SND.ctx) SND.ctx.suspend(); cancelAnimationFrame(raf); raf = 0; }
    else resume();
  }
  function catchUpNow() {
    var now = Date.now(), inp = readInputs(st.simTs), xs = inp.xpEvents, base = inp.xpBefore;
    var lvAt = function (ts) { var x = base; for (var i = 0; i < xs.length && xs[i][0] <= ts; i++) x += xs[i][1]; return levelOf(x); };
    var rep = R.catchUp(st, inp.shifts, lvAt, now, st.auto ? function (l) { return R.autoUp(st, l, bpTotal); } : null);
    inpT = now; save();
    return rep;
  }
  function resume() {
    if (!mounted || !st) return;
    report(catchUpNow());
    buildBG(); shopSig = ''; ui();
    if (SND.on && SND.ctx) { SND.ctx.resume(); }
    if (!raf) { last = performance.now(); raf = requestAnimationFrame(loop); }
  }
  function mount(el) {
    root = el; mounted = true;
    var raw = ''; try { raw = A.rpgLoad(); } catch (e) {}
    var now = Date.now();
    try { st = raw ? JSON.parse(raw) : null; } catch (e) { st = null; }
    var fresh = !st;
    if (!st) st = R.newState(now);
    var def = R.newState(now);
    for (var k in def) if (st[k] == null) st[k] = def[k];
    ['eq', 'sk', 'total'].forEach(function (k) { for (var j in def[k]) if (st[k][j] == null) st[k][j] = def[k][j]; });
    root.innerHTML =
      '<div class="rhead"><div class="rlv" id="rpgLv">Lv 1</div><div style="flex:1;min-width:0"><div style="font-size:12px;color:var(--sub)">용사 · <span id="rpgCls"></span></div>' +
      '<div style="font-size:17px;font-weight:900">전투력 <span id="rpgPow">0</span></div></div>' +
      '<div class="rcur"><div><i class="coin"></i><b id="rpgGold">0</b></div><div><i class="gem"></i><b id="rpgBp">0</b></div></div></div>' +
      '<div class="rstage"><div><span class="muted" style="font-size:12px">STAGE</span> <b id="rpgStage">1-1</b> <b id="rpgBiome" style="margin-left:4px"></b></div><div class="rpips" id="rpgPips"></div></div>' +
      '<div class="rscene"><canvas id="rpgCv"></canvas><div class="ridle" id="rpgIdle"><div><b>휴식 중</b><br>운행을 시작하면 자동 사냥이 시작돼요</div></div>' +
      '<div class="rtag" id="rpgKills"></div><button class="rsnd" id="rpgSnd">소리 켜기</button></div>' +
      '<div class="card" style="margin-top:12px"><div style="font-size:13px" id="rpgNeed"></div><div class="qbar"><i id="rpgNeedBar"></i></div>' +
      '<div class="muted" style="font-size:12px;margin-top:8px" id="rpgStats"></div></div>' +
      '<div class="card"><div class="row between" style="margin-bottom:10px"><div class="seg" style="margin:0;flex:1"><button id="rpgTabEq" class="on">장비 (골드)</button><button id="rpgTabSk">스킬 (배달 포인트)</button></div></div>' +
      '<div id="rpgShop"></div><button class="btn pri" id="rpgAuto" style="width:100%;margin-top:10px">한 번에 강화</button>' +
      '<label class="row between" style="margin-top:12px;font-size:14px"><span>사냥 중 자동 강화<div class="muted" style="font-size:11px">켜 두면 운행 중에도 알아서 강화해서 막히지 않아요</div></span><span class="sw"><input type="checkbox" id="rpgAutoSw"><span></span></span></label>' +
      '<div class="muted" style="font-size:11px;margin-top:10px;line-height:1.5">골드는 사냥으로, 배달 포인트는 배달료 100원당 1점 + 퀘스트 완료 1개당 100점으로 쌓여요. 레벨과 전직은 실제 배달 경험치를 따라가요.</div></div>';
    cv = $('rpgCv'); ctx = cv.getContext('2d'); buf = document.createElement('canvas'); bx = buf.getContext('2d');
    $('rpgTabEq').onclick = function () { shopTab = 'eq'; shopSig = ''; renderShop(); };
    $('rpgTabSk').onclick = function () { shopTab = 'sk'; shopSig = ''; renderShop(); };
    $('rpgAuto').onclick = autoUp;
    $('rpgAutoSw').checked = !!st.auto;
    $('rpgAutoSw').onchange = function () { st.auto = this.checked; save(); };
    $('rpgShop').onclick = function (e) { var b = e.target.closest('[data-up]'); if (b) upgrade(b.dataset.up); };
    $('rpgSnd').onclick = function () { setSound(!SND.on); };
    document.addEventListener('visibilitychange', onVis);
    window.addEventListener('resize', resize);
    var go = function () {
      resize();
      if (fresh) { readInputs(now); st.cls = R.cls(lv); save(); modal('<div style="font-size:22px;font-weight:900">사냥터에 오신 걸 환영해요</div><div style="margin-top:10px;font-size:14px;line-height:1.6;opacity:.85">운행 중에는 용사가 알아서 사냥해요. 쉬는 시간에 들어와서 골드와 배달 포인트로 강화하면 더 깊은 사냥터로 갈 수 있어요.</div>'); }
      else report(catchUpNow());
      if (st.cls != null && R.cls(lv) > st.cls) { modal('<div style="font-weight:800;letter-spacing:3px;opacity:.8">CLASS UP</div><div class="big-lv" style="font-size:34px">전직! ' + R.CLASSES[R.cls(lv)].name + '</div><div style="margin-top:8px;opacity:.8">공격력이 크게 올랐어요</div>'); }
      st.cls = R.cls(lv);
      buildBG(); ui();
      var snd = false; try { snd = localStorage.getItem('rpgSound') === '1'; } catch (e) {}
      if (snd) setSound(true);
      last = performance.now(); raf = requestAnimationFrame(loop);
    };
    if (Object.keys(IMG).length) go(); else loadImages(go);
  }
  function unmount() {
    if (!mounted) return;
    mounted = false; cancelAnimationFrame(raf); raf = 0; save();
    if (SND.ctx) { bgm(null); SND.ctx.suspend(); }
    document.removeEventListener('visibilitychange', onVis);
    window.removeEventListener('resize', resize);
  }
  // 홈 화면 요약용 (그림 파일 없이도 동작)
  function summary(raw) {
    try { var s = JSON.parse(raw); var w = R.where(s.g); return { label: (w.loop > 1 ? w.loop + '회차 ' : '') + w.label, biome: R.BIOMES[w.biome].name, gold: fmt(s.gold) }; } catch (e) { return null; }
  }
  function pause() { if (!mounted) return; save(); if (SND.ctx) SND.ctx.suspend(); cancelAnimationFrame(raf); raf = 0; }
  return { mount: mount, unmount: unmount, resume: resume, pause: pause, save: save, summary: summary, fmt: fmt, get state() { return st; } };
})();
