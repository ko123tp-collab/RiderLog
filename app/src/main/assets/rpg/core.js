// ===== Rider Log RPG 규칙 (전투 수치·성장 곡선·오프라인 사냥 계산) =====
// 화면 애니메이션과 오프라인 계산이 같은 수치를 쓰도록 여기 한 곳에 모아 둔다.
var RPG = (function () {
  var CLASSES = [
    { min: 1, ch: 'Soldier', name: '병사', mult: 1 },
    { min: 10, ch: 'Swordsman', name: '검사', mult: 1.25 },
    { min: 30, ch: 'Knight', name: '기사', mult: 1.6 },
    { min: 60, ch: 'Knight Templar', name: '템플러 기사', mult: 2.1 }
  ];
  var BIOMES = [
    { name: '푸른 초원', bg: 'Grasslands', mobs: ['Slime', 'Bat', 'Slime'], boss: 'Werewolf', bossName: '초원의 늑대인간' },
    { name: '어둠의 숲', bg: 'Forest', mobs: ['Werewolf', 'Orc', 'Bat'], boss: 'Elite Orc', bossName: '숲의 오크 대장' },
    { name: '잊힌 유적', bg: 'Ruins', mobs: ['Orc', 'Skeleton', 'Slime'], boss: 'Armored Orc', bossName: '유적의 수문장' },
    { name: '망자의 묘지', bg: 'Cemetery', mobs: ['Skeleton', 'Armored Skeleton', 'Skeleton Archer'], boss: 'Greatsword Skeleton', bossName: '대검의 망령' },
    { name: '오크 요새', bg: 'Medieval', mobs: ['Orc', 'Armored Orc', 'Orc rider'], boss: 'Werebear', bossName: '요새의 곰전사' },
    { name: '지하 감옥', bg: 'Dungeon', mobs: ['Armored Skeleton', 'Skeleton Archer', 'Armored Orc'], boss: 'Greatsword Skeleton', bossName: '감옥의 처형자' },
    { name: '수정 동굴', bg: 'Crystal Color 2', mobs: ['Bat', 'Werewolf', 'Slime'], boss: 'Werebear', bossName: '수정 동굴의 주인' },
    { name: '용암 지대', bg: 'Lava', mobs: ['Elite Orc', 'Orc rider', 'Armored Orc'], boss: 'Werebear', bossName: '화염의 곰왕' }
  ];
  // 장비: 골드로 강화
  var EQUIP = {
    weapon: { name: '무기', desc: '공격력', base: 40, growth: 1.1022, per: 0.10 },
    glove: { name: '장갑', desc: '치명타 피해', base: 60, growth: 1.1122, per: 0.12 },
    ring: { name: '반지', desc: '골드 획득', base: 80, growth: 1.16, per: 0.06 }
  };
  // 스킬: 배달 포인트로 강화
  var SKILLS = {
    whirl: { name: '회오리 베기', desc: '8초마다 광역 베기 피해', base: 30, growth: 1.12, max: 999 },
    focus: { name: '집중', desc: '공격력', base: 40, growth: 1.11, max: 999 },
    crit: { name: '급소 찌르기', desc: '치명타 확률', base: 50, growth: 1.16, max: 40 },
    haste: { name: '신속', desc: '공격 속도', base: 60, growth: 1.17, max: 30 }
  };
  var KILLS_PER_STAGE = 15, BOSS_LIMIT = 35, WALK = 0.9, SKILL_CD = 8, BOSS_MULT = 10, GATE = 4;
  // 성장 곡선 수치 (시뮬레이션으로 맞춘 값)
  var P = { goldBase: 2, goldG: 1.02, wPer: 1.06, cg: 1.09, fPer: 1.03, lvExp: 1.6 };
  // 스테이지별 몬스터 체력 표 (기준 라이더가 목표 일정대로 깨도록 시뮬레이션으로 만든 값). 표 뒤로는 일정 비율로 늘어난다.
  var HP = [60,107,191,341,608,1080,1940,2590,3480,4660,6250,11200,20200,31000,40700,53500,70300,92300,119000,151000,191000,308000,424000,555000,720000,911000,1.15e+06,1.4e+06,1.69e+06,2.02e+06,2.38e+06,3.17e+06,4.12e+06,5.24e+06,6.53e+06,7.99e+06,9.43e+06,1.1e+07,1.27e+07,1.41e+07,1.57e+07,1.81e+07,2.09e+07,2.38e+07,2.69e+07,3.09e+07,3.38e+07,3.85e+07,4.25e+07,4.57e+07,5e+07,6.57e+07,9.29e+07,1.06e+08,1.24e+08,1.45e+08,1.66e+08,1.99e+08,2.13e+08,2.38e+08,2.56e+08,2.82e+08,3.13e+08,3.46e+08,3.82e+08,4.01e+08,4.42e+08,4.91e+08,5.28e+08,5.68e+08,6.1e+08,6.6e+08,7.19e+08,8.03e+08,8.38e+08,8.79e+08,9.71e+08,1.07e+09,1.17e+09,1.23e+09,1.27e+09,1.33e+09,1.37e+09,1.49e+09,1.56e+09,1.66e+09,1.7e+09,1.79e+09,1.92e+09,2.06e+09,2.12e+09,2.21e+09,2.28e+09,2.45e+09,2.56e+09,2.72e+09,2.77e+09,2.89e+09,3.11e+09,3.42e+09,4.42e+09,4.55e+09,4.8e+09,4.89e+09,5.19e+09,5.36e+09,5.69e+09,5.83e+09,5.97e+09,6.56e+09,6.69e+09,6.83e+09,6.96e+09,7.1e+09,7.36e+09,7.69e+09,7.9e+09,8.35e+09,8.64e+09,8.81e+09,9.21e+09,9.56e+09,9.75e+09,9.99e+09,1.08e+10,1.1e+10,1.12e+10,1.2e+10,1.23e+10,1.25e+10,1.28e+10,1.3e+10,1.37e+10,1.4e+10,1.43e+10,1.51e+10,1.57e+10,1.6e+10,1.69e+10,1.73e+10,1.76e+10,1.79e+10,1.83e+10,1.87e+10,1.9e+10,1.97e+10,2.02e+10,2.13e+10,2.17e+10,2.34e+10,2.38e+10,2.43e+10,2.48e+10,2.53e+10,2.58e+10,2.63e+10,2.74e+10,2.79e+10,2.85e+10,2.99e+10,3.05e+10,3.11e+10,3.17e+10,3.25e+10,3.4e+10,3.47e+10,3.54e+10,3.61e+10,3.73e+10,3.87e+10,4.07e+10,4.16e+10,4.24e+10,4.32e+10,4.41e+10,4.5e+10,4.62e+10,4.72e+10,4.81e+10,4.97e+10,5.13e+10,5.23e+10,5.34e+10,5.45e+10,5.62e+10,5.77e+10,5.89e+10,6e+10,6.19e+10,6.31e+10,6.44e+10,6.77e+10,6.93e+10,7.07e+10,7.21e+10,7.36e+10,7.5e+10,7.65e+10,7.81e+10,7.96e+10,8.12e+10,8.36e+10,8.54e+10,8.71e+10,8.88e+10,9.06e+10,9.36e+10,9.55e+10,9.74e+10,1.02e+11,1.04e+11,1.08e+11,1.11e+11,1.13e+11,1.15e+11,1.17e+11,1.2e+11,1.22e+11,1.25e+11,1.27e+11,1.3e+11,1.32e+11,1.35e+11,1.39e+11,1.44e+11,1.47e+11,1.5e+11,1.53e+11,1.56e+11,1.59e+11,1.63e+11,1.66e+11,1.69e+11,1.72e+11,1.76e+11,1.8e+11,1.83e+11,1.94e+11,1.98e+11,2.02e+11];

  function cls(lv) { var c = 0; for (var i = 0; i < CLASSES.length; i++) if (lv >= CLASSES[i].min) c = i; return c; }
  function newState(now) {
    return { v: 1, auto: true, g: 0, kills: 0, gold: 0, bpSpent: 0, simTs: now, eq: { weapon: 0, glove: 0, ring: 0 }, sk: { whirl: 0, focus: 0, crit: 0, haste: 0 },
      total: { kills: 0, gold: 0, bosses: 0, fails: 0 }, best: 0, failNote: 0 };
  }
  function stats(st, lv) {
    var c = CLASSES[cls(lv)];
    var atk = 5 * Math.pow(lv + 2, P.lvExp) * c.mult * Math.pow(P.wPer, st.eq.weapon) * Math.pow(P.fPer, st.sk.focus);
    var critC = Math.min(0.6, 0.10 + 0.0125 * st.sk.crit);
    var critD = 2 + EQUIP.glove.per * st.eq.glove;
    var interval = 0.7 / (1 + 0.04 * st.sk.haste);
    var skillMult = 3 + 0.4 * st.sk.whirl;
    var avg = atk * (1 + critC * (critD - 1));
    var dps = avg / interval + avg * skillMult / SKILL_CD;
    return { atk: atk, critC: critC, critD: critD, interval: interval, skillMult: skillMult, dps: dps, power: Math.round(dps * 10), cls: c };
  }
  function hp(g) { var n = HP.length; if (!n) return 60 * Math.pow(1.1, g); return g < n ? HP[g] : HP[n - 1] * Math.pow(HP[n - 1] / HP[n - 11], (g - n + 1) / 10); }
  function bossHp(g) { return hp(g) * BOSS_MULT; }
  function gold(g, st) { return P.goldBase * Math.pow(P.goldG, g) * (1 + EQUIP.ring.per * st.eq.ring); }
  function isBoss(g) { return g % 10 === 9; }
  function where(g) { var b = Math.floor(g / 10); return { biome: b % BIOMES.length, stage: g % 10 + 1, loop: Math.floor(b / BIOMES.length) + 1, label: (b % BIOMES.length + 1) + '-' + (g % 10 + 1) }; }
  // 다음 스테이지로 넘어가는 데 필요한 초당 피해량
  function needDps(g) { return isBoss(g) ? bossHp(g) / BOSS_LIMIT : hp(g + 1) / GATE; }
  function canAdvance(g, dps) { return dps >= needDps(g); }
  function needPower(g) { return Math.round(needDps(g) * 10); }
  function eqCost(k, lv) { return Math.round(EQUIP[k].base * Math.pow(P.cg, lv)); }
  function skCost(k, lv) { return Math.round(SKILLS[k].base * Math.pow(SKILLS[k].growth, lv)); }

  // 오프라인 사냥 계산: sec 초 동안 사냥. lv 는 그 구간의 레벨.
  function hunt(st, lv, sec, rep) {
    var s = stats(st, lv), t = sec;
    while (t > 0) {
      if (isBoss(st.g)) {
        var need = bossHp(st.g) / s.dps;
        if (need <= BOSS_LIMIT) {
          if (t < need + 2) { t = 0; break; }
          t -= need + 2;
          var gb = gold(st.g, st) * 30; st.gold += gb; st.total.gold += gb; rep.gold += gb;
          st.total.bosses++; rep.bosses++; st.g++; st.kills = 0; rep.stages++;
        } else {
          // 보스 실패 → 앞 스테이지에서 한 바퀴 더 사냥하고 다시 도전
          if (t < BOSS_LIMIT + 2) { t = 0; break; }
          t -= BOSS_LIMIT + 2; st.total.fails++; rep.fails++;
          var per = hp(st.g - 1) / s.dps + WALK, n = Math.min(KILLS_PER_STAGE, Math.floor(t / per));
          var gg = gold(st.g - 1, st) * n; t -= n * per; st.gold += gg; st.total.gold += gg; rep.gold += gg; st.total.kills += n; rep.kills += n;
          if (n < KILLS_PER_STAGE) t = 0;
        }
      } else {
        var per2 = hp(st.g) / s.dps + WALK, left = KILLS_PER_STAGE - st.kills, n2 = Math.min(left, Math.floor(t / per2));
        if (n2 <= 0) { t = 0; break; }
        t -= n2 * per2; st.kills += n2; var g2 = gold(st.g, st) * n2;
        st.gold += g2; st.total.gold += g2; rep.gold += g2; st.total.kills += n2; rep.kills += n2;
        if (st.kills >= KILLS_PER_STAGE) {
          st.kills = 0;
          // 다음 스테이지 몬스터를 4초 안에 잡을 수 있어야 넘어간다 (못 넘어가면 이 스테이지에서 계속 사냥)
          if (canAdvance(st.g, s.dps)) { st.g++; rep.stages++; } else rep.stuck = true;
        }
      }
    }
    st.best = Math.max(st.best, st.g);
  }
  // 운행 구간 [[시작,끝],...] 과 레벨 함수 lvAt(ts) 로 simTs 이후를 계산
  // onStep(lv): 5분마다 호출 (자동 강화용, 없어도 됨)
  function catchUp(st, shifts, lvAt, now, onStep) {
    var rep = { sec: 0, kills: 0, gold: 0, stages: 0, bosses: 0, fails: 0, from: st.g };
    var STEP = 300000; // 5분 단위로 레벨 반영
    for (var i = 0; i < shifts.length; i++) {
      var a = Math.max(shifts[i][0], st.simTs), b = Math.min(shifts[i][1] || now, now);
      for (var t = a; t < b; t += STEP) {
        var e = Math.min(b, t + STEP), sec = (e - t) / 1000;
        if (onStep) rep.ups = (rep.ups || 0) + (onStep(lvAt(t)) || 0);
        hunt(st, lvAt(e), sec, rep); rep.sec += sec;
      }
    }
    st.simTs = now; rep.to = st.g;
    return rep;
  }
  function eqUp(st, k) { var c = eqCost(k, st.eq[k]); if (st.gold < c) return false; st.gold -= c; st.eq[k]++; return true; }
  function skUp(st, k, bp) { var c = skCost(k, st.sk[k]); if (st.sk[k] >= SKILLS[k].max || bp - st.bpSpent < c) return false; st.bpSpent += c; st.sk[k]++; return true; }
  // 가장 효율 좋은 강화부터 가능한 만큼 (한 번에 강화)
  function autoUp(st, lv, bp) {
    var n = 0, guard = 0;
    while (guard++ < 2000) {
      var base = stats(st, lv).dps, best = null, bestV = 0;
      ['weapon', 'glove'].forEach(function (k) {
        var c = eqCost(k, st.eq[k]); if (c > st.gold) return;
        st.eq[k]++; var v = (stats(st, lv).dps - base) / c; st.eq[k]--;
        if (v > bestV) { bestV = v; best = ['e', k]; }
      });
      // 반지는 골드 효율: 무기 강화 비용의 절반 이하일 때만
      var rc = eqCost('ring', st.eq.ring);
      if (rc <= st.gold && rc < eqCost('weapon', st.eq.weapon) * 0.6) best = best || ['e', 'ring'];
      if (!best) break;
      eqUp(st, best[1]); n++;
    }
    guard = 0;
    while (guard++ < 2000) {
      var base2 = stats(st, lv).dps, best2 = null, bv = 0;
      Object.keys(SKILLS).forEach(function (k) {
        var c = skCost(k, st.sk[k]); if (st.sk[k] >= SKILLS[k].max || c > bp - st.bpSpent) return;
        st.sk[k]++; var v = (stats(st, lv).dps - base2) / c; st.sk[k]--;
        if (v > bv) { bv = v; best2 = k; }
      });
      if (!best2) break;
      skUp(st, best2, bp); n++;
    }
    return n;
  }
  return { P: P, HP: HP, CLASSES: CLASSES, BIOMES: BIOMES, EQUIP: EQUIP, SKILLS: SKILLS, KILLS_PER_STAGE: KILLS_PER_STAGE, BOSS_LIMIT: BOSS_LIMIT, SKILL_CD: SKILL_CD, WALK: WALK,
    BOSS_MULT: BOSS_MULT, GATE: GATE, needDps: needDps, canAdvance: canAdvance, cls: cls, newState: newState, stats: stats, hp: hp, bossHp: bossHp, gold: gold, isBoss: isBoss, where: where, needPower: needPower,
    eqCost: eqCost, skCost: skCost, hunt: hunt, catchUp: catchUp, eqUp: eqUp, skUp: skUp, autoUp: autoUp };
})();
if (typeof module !== 'undefined') module.exports = RPG;
