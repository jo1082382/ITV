package com.example.itv.infrastructure.observation.diagnostics


import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** In-memory bounded structural log. Never reads text, descriptions or video identities. */
internal object YoutubeStructureDiag {
    var enabled = false
    var status = "구조 진단 중지"
    private val lines = java.util.ArrayDeque<String>()
    private var previous: Map<String,String>? = null
    private var lastSample = 0L
    private var started = 0L
    private var frames = 0
    private var changes = 0
    private var lastSummary = 0L
    private val eventCounts = mutableMapOf<Int,Int>()
    fun reset() {
        lines.clear(); previous=null; lastSample=0L; frames=0; changes=0
        eventCounts.clear(); started=SystemClock.elapsedRealtime(); lastSummary=0L
    }
    private fun add(s: String) {
        if (started == 0L) started=SystemClock.elapsedRealtime()
        lines.addLast("+${SystemClock.elapsedRealtime()-started}ms $s")
        while(lines.size>220) lines.removeFirst()
    }
    fun mark(label: String) { add("MARK $label"); previous=null }
    fun report(): String = "구조진단 enabled=$enabled · $status\n관측 $frames · 구조차이 $changes (영상 전환 수 아님)\n이벤트종류별수 $eventCounts\n" + lines.joinToString("\n")
    fun event(e: AccessibilityEvent?, service: AccessibilityService) {
        if (!enabled || !com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(service) || e?.packageName?.toString()!="com.google.android.youtube") return
        val t=e.eventType
        eventCounts[t]=(eventCounts[t] ?: 0)+1
        if (t!=AccessibilityEvent.TYPE_VIEW_SCROLLED && t!=AccessibilityEvent.TYPE_VIEW_SELECTED &&
            t!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && t!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val source=e.source
        try {
            val r=Rect(); source?.getBoundsInScreen(r)
            add("EVENT ${AccessibilityEvent.eventTypeToString(t)} from=${e.fromIndex} to=${e.toIndex} count=${e.itemCount} scroll=${e.scrollX},${e.scrollY} max=${e.maxScrollX},${e.maxScrollY} source=${source?.viewIdResourceName} cls=${source?.className} bounds=$r")
        } finally { if(source!=null) { @Suppress("DEPRECATION") source.recycle() } }
    }
    fun tick(service: AccessibilityService) {
        if (!enabled) return
        if (!com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(service)) { status="수집 동의 없음"; previous=null; return }
        val now=SystemClock.elapsedRealtime()
        if(now-lastSample<450L) return
        lastSample=now
        try {
            val root=service.windows.orEmpty().firstOrNull { it.type==AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
                ?: run { status="화면 판별 불가"; previous=null; return }
            try {
                if(root.packageName?.toString()!="com.google.android.youtube") { status="YouTube 전면 아님 · 로그 보존"; previous=null; return }
                val map=linkedMapOf<String,String>(); var visited=0; var truncated=false
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo,path:String,depth:Int) {
                    if(++visited>900 || depth>40) { truncated=true; return }
                    if(!n.isVisibleToUser) return
                    val r=Rect(); n.getBoundsInScreen(r)
                    val item=n.collectionItemInfo
                    val collection=n.collectionInfo
                    // Quantize coordinates to 16dp bins to reduce tiny animation noise.
                    val unit=(16*service.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    val value="id=${n.viewIdResourceName} cls=${n.className} selected=${n.isSelected} scrollable=${n.isScrollable} box=${r.left/unit},${r.top/unit},${r.right/unit},${r.bottom/unit} item=${item?.rowIndex},${item?.columnIndex} collection=${collection?.rowCount},${collection?.columnCount}"
                    map[path]=value
                    for(i in 0 until n.childCount) {
                        if(visited>=900) { truncated=true; break }
                        val child=n.getChild(i) ?: continue
                        try { walk(child,"$path/$i",depth+1) } finally { @Suppress("DEPRECATION") child.recycle() }
                    }
                }
                walk(root,"0",0)
                if(truncated) { status="검사 한도 초과 · 비교 제외"; previous=null; return }
                frames++; status="YouTube 구조 관측 중 (진단용)"
                val old=previous
                if(old==null) {
                    add("BASE nodes=${map.size}")
                    map.entries.take(28).forEach { add("BASE ${it.key} ${it.value}") }
                } else {
                    val keys=(old.keys+map.keys).filter { old[it]!=map[it] }
                    if(keys.isNotEmpty()) {
                        changes++
                        add("DIFF count=${keys.size} nodes=${map.size}")
                        keys.take(10).forEach { key -> add("$key OLD=${old[key]} NEW=${map[key]}") }
                    }
                }
                if(now-lastSummary>=10000L) { add("HEARTBEAT frames=$frames changes=$changes");lastSummary=now }
                previous=map
            } finally { @Suppress("DEPRECATION") root.recycle() }
        } catch (_:Exception) { status="구조 검사 실패"; previous=null }
    }
}
