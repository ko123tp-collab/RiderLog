package com.rider.log

/**
 * 배민커넥트(com.woowahan.bros) 화면 글자로 배달 단계를 자동 인식.
 * 2026-11-28 실제 운행 화면 기록으로 확인한 규칙:
 *  - 콜:   #신규배차_카드, '픽업지' 다음 = 가게, '배달료' 다음 = 금액, 'NN초' = 남은 시간
 *  - 수락: #신규배차_수락버튼 글자가 '배차 수락됨' 또는 '배달리스트에 추가되었습니다'
 *  - 거절/시간초과: 수락 없이 콜 카드가 사라짐 (남은 시간 3초 이하였으면 시간초과)
 *  - 도착: #ai-mode-delivery-status-cta-button 글자가 '가게 도착' → '픽업 완료'로 바뀜
 *  - 조리완료: 가게 상태 '조리완료' 표시
 *  - 픽업: 같은 버튼이 '픽업 완료' → '전달 완료'로 바뀜 (또는 '픽업 완료 되었습니다')
 *  - 완료: '배달완료' 창 + '배달료' 다음 금액
 */
class Recognizer {

    data class N(val id: String?, val t: String)

    /** 인식 결과를 받아 기록하는 쪽 (TrackingService) */
    interface Sink {
        fun autoEvent(type: String, ts: Long, store: String?, amount: Int?, note: String?, addr: String?)
        fun autoEndShift()
    }

    // 콜 상태
    private var offerKey: String? = null
    private var offerAccepted = false
    private var offerLastSec = 99
    private var offerAddr: String? = null
    private val pickAddr = HashMap<String, String>()
    private val dropAddr = HashMap<String, String>()

    // 주문 진행 상태 (주문번호 또는 가게 이름 기준)
    private val lastCta = HashMap<String, String>()
    private val done = HashMap<String, MutableSet<String>>()
    private var loadingTs = 0L
    private var curStore: String? = null
    private var curOrder: String? = null
    private var lastDeliverTs = 0L
    private var endPopupTs = 0L

    private val fareRe = Regex("^([\\d,]+)\\s*원$")
    private val secRe = Regex("^(\\d+)\\s*초$")
    private val codeRe = Regex("^[A-Z0-9]{4}$")

    private fun idx(nodes: List<N>, id: String): Int = nodes.indexOfFirst { it.id == id }

    /** id가 붙은 버튼 바로 다음 글자 (로딩 중이면 "LOADING") */
    private fun textAfterId(nodes: List<N>, id: String): String? {
        val i = idx(nodes, id)
        if (i < 0) return null
        for (k in i + 1 until minOf(nodes.size, i + 3)) {
            val n = nodes[k]
            if (n.id == "react-button-loading-progress-img") return "LOADING"
            if (n.t.isNotBlank()) return n.t.trim()
        }
        return null
    }

    /** 라벨 글자 다음에 나오는 첫 글자 */
    private fun after(nodes: List<N>, label: String): String? {
        return afterList(nodes, label, 1).firstOrNull()
    }

    /** 라벨 다음에 나오는 글자들 (라벨과 같은 글자는 건너뜀) */
    private fun afterList(nodes: List<N>, label: String, n: Int): List<String> {
        val i = nodes.indexOfFirst { it.t.trim() == label }
        if (i < 0) return emptyList()
        val out = ArrayList<String>()
        for (k in i + 1 until minOf(nodes.size, i + 8)) {
            val t = nodes[k].t.trim()
            if (t.isNotBlank() && t != label) out.add(t)
            if (out.size >= n) break
        }
        return out
    }

    private val addrHint = Regex("(로|길|동|읍|면|리)\\s?\\d|\\d+\\s?(번지|호|층)|\\[주소\\]|(시|구)\\s")
    private fun looksAddr(s: String?): String? = s?.takeIf { addrHint.containsMatchIn(it) && it.length >= 4 }

    private fun fare(s: String?): Int? = s?.let { fareRe.find(it.trim())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }

    private fun once(key: String, type: String): Boolean = done.getOrPut(key) { HashSet() }.add(type)

    fun onScreen(nodes: List<N>, now: Long, sink: Sink) {
        val ids = nodes.mapNotNull { it.id }.toHashSet()
        val texts = nodes.map { it.t.trim() }

        // ---------- 운행 종료 연동 ----------
        if ("운행종료팝업_확인버튼" in ids) endPopupTs = now
        if ("start-work-button" in ids && endPopupTs > 0 && now - endPopupTs < 120_000) {
            endPopupTs = 0
            sink.autoEndShift()
            return
        }

        // ---------- 콜 ----------
        if ("신규배차_카드" in ids) {
            val store = after(nodes, "픽업지")
            val amount = fare(after(nodes, "배달료"))
            val sec = texts.firstNotNullOfOrNull { secRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val btn = textAfterId(nodes, "신규배차_수락버튼") ?: ""
            looksAddr(after(nodes, "전달지"))?.let { offerAddr = it }
            val key = (store ?: "?") + "|" + (amount ?: 0)
            // 수락 직후 화면은 금액 자리에 다른 글자가 나와서, 가게가 같으면 같은 콜로 봄
            val same = offerKey != null && offerKey?.substringBefore("|") == store
            if (!same && store != null) {
                finishOffer(texts, now, sink)
                offerKey = key
                offerAccepted = false
                val kind = texts.firstOrNull { it.startsWith("배민") } ?: ""
                offerAddr = looksAddr(after(nodes, "전달지"))
                sink.autoEvent("OFFER", now, store, amount, kind, offerAddr)
            }
            if (sec != null) offerLastSec = sec
            if (!offerAccepted && (btn.contains("수락됨") || texts.any { it.contains("배달리스트에 추가되었습니다") })) {
                offerAccepted = true
                sink.autoEvent("ACCEPT", now, store, amount, null, offerAddr)
            }
        } else if (offerKey != null) {
            finishOffer(texts, now, sink)
        }

        // ---------- 주문 진행 ----------
        after(nodes, "가게명")?.let { curStore = it }
        val ci = texts.indexOf("주문정보")
        if (ci >= 0) {
            for (k in ci + 1 until minOf(texts.size, ci + 4)) if (codeRe.matches(texts[k])) { curOrder = texts[k]; break }
        }
        after(nodes, "주문번호")?.let { if (codeRe.matches(it)) curOrder = it }
        val key = curOrder ?: curStore

        if (key != null) {
            // 가게 주소: '가게전화' 다음 = 가게 이름, 그다음 = 주소
            val pk = afterList(nodes, "가게전화", 2)
            if (pk.size == 2) looksAddr(pk[1])?.let { pickAddr[key] = it }
            // 전달지 주소: '고객연결' 다음, 또는 사진 화면의 주문번호 다음
            looksAddr(after(nodes, "고객연결"))?.let { dropAddr[key] = it }
            curOrder?.let { code -> looksAddr(after(nodes, code))?.let { if (texts.contains("주문번호")) dropAddr[key] = it } }
        }

        val cta = textAfterId(nodes, "ai-mode-delivery-status-cta-button")
        if (key != null && cta != null) {
            if (cta == "LOADING") {
                loadingTs = now
            } else {
                val prev = lastCta[key]
                val ts = if (now - loadingTs < 5000) loadingTs else now
                if (cta == "픽업 완료" && prev == "가게 도착" && once(key, "ARRIVE")) {
                    sink.autoEvent("ARRIVE", ts, curStore, null, curOrder, pickAddr[key])
                }
                if (cta == "전달 완료" && prev == "픽업 완료" && once(key, "PICKUP")) {
                    sink.autoEvent("PICKUP", ts, curStore, null, curOrder, pickAddr[key])
                }
                lastCta[key] = cta
            }
            if ((cta == "가게 도착" || cta == "픽업 완료") && texts.contains("조리완료") && once(key, "READY")) {
                sink.autoEvent("READY", now, curStore, null, curOrder, null)
            }
        }
        // '픽업 완료 되었습니다' 안내는 화면에 오래 남아서, 이 주문이 픽업 단계일 때만 인정
        if (key != null && lastCta[key] == "픽업 완료" && texts.any { it.contains("픽업 완료 되었습니다") } && once(key, "PICKUP")) {
            sink.autoEvent("PICKUP", now, curStore, null, curOrder, pickAddr[key])
            lastCta[key] = "전달 완료"
        }

        // ---------- 배달 완료 ----------
        if (texts.contains("배달완료") && now - lastDeliverTs > 20_000) {
            val amount = fare(after(nodes, "배달료"))
            if (amount != null) {
                lastDeliverTs = now
                if (key == null || once(key, "DELIVER")) sink.autoEvent("DELIVER", now, curStore, amount, curOrder, key?.let { dropAddr[it] } ?: offerAddr)
                curOrder = null
            }
        }
    }

    private fun finishOffer(texts: List<String>, now: Long, sink: Sink) {
        if (offerKey == null) return
        if (!offerAccepted) {
            if (texts.any { it.contains("배달리스트에 추가되었습니다") }) {
                sink.autoEvent("ACCEPT", now, offerKey?.substringBefore("|"), null, null, offerAddr)
            } else {
                sink.autoEvent("REJECT", now, offerKey?.substringBefore("|"), null, if (offerLastSec <= 3) "시간초과" else "거절", null)
            }
        }
        offerKey = null
        offerAccepted = false
        offerLastSec = 99
    }
}
