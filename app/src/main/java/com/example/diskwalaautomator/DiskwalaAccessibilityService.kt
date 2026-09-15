package com.example.diskwalaautomator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject

class DiskwalaAccessibilityService : AccessibilityService() {

    companion object {
        const val TARGET_PACKAGE = "com.diskwala.app" // TODO: real package name
        val AD_CLOSE_LABELS = listOf("Close ad", "Close", "Skip", "×", "Skip Ad")

        const val POLL_MS = 700L
        const val STEP_TIMEOUT_MS = 15_000L
        const val NEXT_PAGE_TEXT = "Next"
    }

    private enum class Mode { IDLE, RECORDING, PLAYING }

    data class RecordedStep(
        val text: String?,
        val desc: String?,
        val viewId: String?,
        val className: String?,
        val bounds: Rect
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("text", text)
            put("desc", desc)
            put("viewId", viewId)
            put("className", className)
            put("left", bounds.left); put("top", bounds.top)
            put("right", bounds.right); put("bottom", bounds.bottom)
        }
        companion object {
            fun fromJson(o: JSONObject): RecordedStep {
                val r = Rect(o.getInt("left"), o.getInt("top"), o.getInt("right"), o.getInt("bottom"))
                return RecordedStep(
                    o.optString("text").ifEmpty { null },
                    o.optString("desc").ifEmpty { null },
                    o.optString("viewId").ifEmpty { null },
                    o.optString("className").ifEmpty { null },
                    r
                )
            }
        }
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE
    private var recordedSteps = mutableListOf<RecordedStep>()
    private var playIndex = 0
    private var waitingSince = 0L

    override fun onServiceConnected() {
        prefs = getSharedPreferences("diskwala_automator", MODE_PRIVATE)
        loadRecordedSteps()
        log("Service connected. ${recordedSteps.size} recorded step(s) loaded.")
        tick()
    }

    private fun tick() {
        handler.postDelayed({
            val desired = prefs.getString("mode", "idle") ?: "idle"
            when (desired) {
                "recording" -> if (mode != Mode.RECORDING) startRecording()
                "playing" -> if (mode != Mode.PLAYING) startPlaying()
                else -> if (mode != Mode.IDLE) stopAll()
            }
            if (mode == Mode.PLAYING) playStep()
            tick()
        }, POLL_MS)
    }

    private fun startRecording() {
        mode = Mode.RECORDING
        recordedSteps.clear()
        log("Recording started — perform the flow once in Diskwala now.")
    }

    private fun startPlaying() {
        loadRecordedSteps()
        if (recordedSteps.isEmpty()) {
            log("No recorded steps found — record a flow first.")
            prefs.edit().putString("mode", "idle").apply()
            return
        }
        mode = Mode.PLAYING
        playIndex = 0
        waitingSince = 0L
        log("Playback started with ${recordedSteps.size} step(s).")
    }

    private fun stopAll() {
        if (mode == Mode.RECORDING) persistRecordedSteps()
        log("Stopped (${recordedSteps.size} step(s) saved).")
        mode = Mode.IDLE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (mode != Mode.RECORDING || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        val src = event.source ?: return
        val bounds = Rect()
        src.getBoundsInScreen(bounds)
        val step = RecordedStep(
            src.text?.toString(),
            src.contentDescription?.toString(),
            src.viewIdResourceName,
            src.className?.toString(),
            bounds
        )
        recordedSteps.add(step)
        persistRecordedSteps()
        log("Recorded tap ${recordedSteps.size}: ${describe(step)}")
    }

    override fun onInterrupt() {
        log("Service interrupted.")
    }

    private fun playStep() {
        if (handleInterruptionIfAny()) return
        if (playIndex >= recordedSteps.size) {
            playIndex = 0
            waitingSince = 0L
            val root = rootInActiveWindow ?: return
            if (findFirstClickableChild(root) != null) {
                log("Replaying sequence for next video.")
            } else {
                val nextPage = findNodeByText(root, NEXT_PAGE_TEXT)
                if (nextPage != null && nextPage.isEnabled) {
                    click(nextPage)
                    log("Navigated to next page.")
                } else {
                    log("No more videos or pages. Playback complete.")
                    prefs.edit().putString("mode", "idle").apply()
                    mode = Mode.IDLE
                }
            }
            return
        }

        val root = rootInActiveWindow ?: return
        val step = recordedSteps[playIndex]
        val node = findNodeForStep(root, step)

        if (node != null) {
            click(node)
            log("Played step ${playIndex + 1}/${recordedSteps.size}: ${describe(step)}")
            playIndex++
            waitingSince = 0L
        } else {
            if (waitingSince == 0L) waitingSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - waitingSince > STEP_TIMEOUT_MS) {
                tapAt(step.bounds.centerX(), step.bounds.centerY())
                log("Step ${playIndex + 1}: matched element not found — tapped recorded position instead.")
                playIndex++
                waitingSince = 0L
            }
        }
    }

    private fun findNodeForStep(root: AccessibilityNodeInfo, step: RecordedStep): AccessibilityNodeInfo? {
        step.viewId?.let { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        step.text?.let { t ->
            root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        step.desc?.let { d ->
            findNodeByDescription(root, d)?.let { return it }
        }
        return null
    }

    private fun handleInterruptionIfAny(): Boolean {
        val currentPkg = rootInActiveWindow?.packageName?.toString()

        if (currentPkg != null && currentPkg != TARGET_PACKAGE) {
            log("Left target app (now in $currentPkg) — returning.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(it)
            }
            return true
        }

        val root = rootInActiveWindow ?: return false
        for (label in AD_CLOSE_LABELS) {
            val closeBtn = findNodeByText(root, label) ?: findNodeByDescription(root, label)
            if (closeBtn != null && closeBtn.isClickable) {
                click(closeBtn)
                log("Closed ad overlay ('$label').")
                return true
            }
        }
        return false
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser }

    private fun findNodeByDescription(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNodeByDescription(child, desc)?.let { return it }
        }
        return null
    }

    private fun findFirstClickableChild(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isClickable && root.className?.contains("Image") == true) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findFirstClickableChild(child)?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        (target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tapAt(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun persistRecordedSteps() {
        val arr = JSONArray()
        recordedSteps.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("recorded_steps", arr.toString()).apply()
    }

    private fun loadRecordedSteps() {
        val raw = prefs.getString("recorded_steps", null) ?: return
        val arr = JSONArray(raw)
        recordedSteps = (0 until arr.length()).map { RecordedStep.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    private fun describe(step: RecordedStep): String =
        step.text ?: step.desc ?: step.viewId ?: "(unlabeled element)"

    private fun log(message: String) {
        val intent = Intent("diskwala_automator_log")
        intent.putExtra("line", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
