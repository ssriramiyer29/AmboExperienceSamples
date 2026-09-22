package com.ambokit.aep.airdraw.tv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.AirDrawPoint
import com.ambokit.aep.airdraw.HandInput
import com.ambokit.aep.airdraw.HandInterpreter
import com.ambokit.aep.airdraw.StrokeSource
import com.ambokit.aep.airdraw.ToolStrip
import com.ambokit.aep.airdraw.ZoomGesture
import com.ambokit.aep.core.Aep
import com.ambokit.aep.core.AepCapabilityEvent
import com.ambokit.aep.core.AepConnectionState
import com.ambokit.aep.core.AepExperienceDefinition
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.core.capabilities.AepHandHandedness
import com.ambokit.aep.host.AepPayloadJson
import com.ambokit.aep.host.ambokit.EmbeddedAndroidAmboKitHost
import com.ambokit.aep.host.tv.AndroidTvExperienceHost
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/**
 * AirDraw: the TV is the canvas and your hand in the air is the pen.
 *
 * One capability, `camera.hand`. Whichever hand pinches is the pen - there is nothing to set up
 * and nothing to choose. The palette is a strip down one edge that is always there, and a pinch
 * that begins inside it takes what it is over.
 *
 * Neither the drawing rules nor the gesture interpretation live here. This class owns the screen,
 * the session and the frame clock, and nothing else - ADR-0001 rule 2. Everything that decides
 * what the line looks like is in `airdraw-core`, which has tests and needs no TV.
 */
class MainActivity : Activity() {

    private lateinit var view: AirDrawView
    private lateinit var host: EmbeddedAndroidAmboKitHost
    private lateinit var session: com.ambokit.aep.core.AepSession
    private lateinit var tvHost: AndroidTvExperienceHost

    private val engine = AirDrawEngine()
    private val hands = HandInterpreter()
    private val zoom = ZoomGesture()
    private val strip = ToolStrip()

    /**
     * Hand frames are conflated before they reach the UI thread, exactly as RockDodge conflates
     * pose. Posting every frame with runOnUiThread builds a backlog seconds deep while the stream
     * itself is current, and a drawing tool that renders a backlog draws the past.
     */
    private val latestHandFrame = AtomicReference<AepHandFrame?>(null)

    private var drawing = false

    /** Whether a pen was present last frame, so a pinch is acted on once rather than every frame. */
    private var hadPen = false

    /** Where the pen was last seen, so a stroke is not bridged across a jump. */
    private var lastPenX: Float? = null
    private var lastPenY: Float? = null

    private var frames = 0L

    /** QR encoding, kept off the thread that has to stay responsive. */
    private val qrWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "airdraw-qr").apply { isDaemon = true }
    }

    private var lastJoinUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = AirDrawView(this, engine, strip)
        setContentView(view)
        engine.colour = strip.colour

        host = EmbeddedAndroidAmboKitHost()
        session = Aep.start(
            AepExperienceDefinition(
                id = "com.ambokit.aep.airdraw",
                // What is declared here is what gets requested (ADR-0002 rule L16). Before AEP
                // 0.8.0 the Android host ignored this and asked for camera.pose regardless, which
                // is why AirDraw needs that release as a floor.
                capabilities = listOf("camera.hand"),
                // AirDraw wants raw positions, not a calibrated body model. Nobody should have to
                // stand still with their arms out before they can draw.
                requiresCalibration = false
            ),
            host
        ).also { live ->
            live.capabilities.event += { event -> onCapabilityEvent(event) }
            live.connectionChanged += { state ->
                runOnUiThread {
                    view.connection = state
                    // A phone that has gone away is not still pinching. Without this the stroke
                    // in progress stays open and joins up with whatever is drawn next.
                    if (state != AepConnectionState.CONNECTED) {
                        letGo()
                        view.streaming = false
                    }
                    view.status = when (state) {
                        AepConnectionState.CONNECTING -> "Starting…"
                        AepConnectionState.CONNECTED -> "Scan the code with Ambo Companion"
                        AepConnectionState.RECONNECTING -> "Reconnecting to your phone…"
                        AepConnectionState.REJOIN_REQUIRED -> "Connection lost — scan again"
                        AepConnectionState.CLOSED -> "Disconnected"
                    }
                    view.invalidate()
                }
            }
            live.joinChanged += { join -> onJoinChanged(join.url) }
            live.error += { error ->
                runOnUiThread { view.status = "${error.code}: ${error.message}"; view.invalidate() }
            }
        }

        tvHost = AndroidTvExperienceHost(session) { _ -> onFrame() }
        tvHost.start()
    }

    /**
     * Put the join URL on screen, and the QR alongside it once it exists.
     *
     * Encoding happens on a worker, because doing it on the main thread is what was killing the
     * app at startup - see [QrCodeBitmap]. The URL appears immediately either way, so a slow
     * encode degrades to "type this in" rather than to a blank screen.
     */
    private fun onJoinChanged(url: String) {
        runOnUiThread {
            view.joinUrl = url
            view.invalidate()
        }
        if (url == lastJoinUrl) return
        lastJoinUrl = url
        qrWorker.execute {
            val bitmap = QrCodeBitmap.create(url)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                // The join can be reissued, and each reissue used to leave the last megabyte of
                // bitmap behind it.
                view.joinQr?.let { if (!it.isRecycled) it.recycle() }
                view.joinQr = bitmap
                view.invalidate()
            }
        }
    }

    private fun letGo() {
        if (drawing) engine.end()
        drawing = false
        hadPen = false
        lastPenX = null
        lastPenY = null
        hands.reset()
        zoom.end()
    }

    /**
     * One display frame: consume the freshest input, then draw.
     *
     * The curve is evaluated here rather than when samples arrive, which is what turns fifteen
     * hand samples a second into a continuous line.
     */
    private fun onFrame() {
        view.sizeViewport()
        consumeHands()
        view.invalidate()
    }

    private fun consumeHands() {
        val frame = latestHandFrame.getAndSet(null) ?: return
        val aspect = engine.viewport.aspect
        val now = System.currentTimeMillis()
        // The envelope only grows while the pen is up, or the mapping would shift under
        // the line being drawn.
        val input = hands.read(frame, aspect, now, learningReach = !drawing)
        view.tracking = input.tracked
        view.cursors = cursorsFor(input)
        try {
            route(input, aspect, now)
        } finally {
            // Logged after the decisions, not before. Logging first printed the previous frame's
            // verdict beside this frame's readings, which is a confusing thing to hand someone
            // who is trying to work out why a stroke ended.
            logInput(input)
        }
    }

    private fun route(input: HandInput, aspect: Float, now: Long) {
        val left = input.left
        val right = input.right
        // Both hands actually seen pinching. A bridged latch is not a second hand for zooming:
        // there is nothing on screen to measure a separation against.
        val bothPinching = left != null && right != null &&
            hands.isSeenPinching(AepHandHandedness.LEFT) && hands.isSeenPinching(AepHandHandedness.RIGHT)

        // Two pinching hands is a zoom, and never a mark. Checked before anything else, so
        // bringing the second hand in does not leave a stray line behind it.
        if (bothPinching) {
            if (drawing) { engine.cancel(); drawing = false }
            hadPen = false
            zoom.update(left, right, true)?.let {
                engine.viewport.zoomAbout(it.focusX, it.focusY, it.scale)
                engine.viewport.panBy(it.panX, it.panY)
            }
            return
        }
        zoom.end()

        // Held and visible are different questions. A pinch survives the hand vanishing for a
        // moment; during that moment there is simply nowhere to draw.
        if (!hands.isPenHeld) {
            if (drawing) engine.end()
            drawing = false
            hadPen = false
            return
        }

        val pen = hands.penHand(input)
        if (pen == null) {
            // The tracker blinked. Hold the stroke open and add nothing to it - the alternative
            // is ending a line the player is still drawing, several times a minute.
            return
        }

        // The hand came back somewhere else entirely. Bridging that would draw a straight line
        // across the picture, which is worse than the seam it was meant to avoid.
        if (drawing && lastPenX != null && lastPenY != null &&
            hypot(pen.x - lastPenX!!, pen.y - lastPenY!!) > REACQUIRE_JUMP
        ) {
            engine.end()
            drawing = false
            hadPen = false
        }
        lastPenX = pen.x
        lastPenY = pen.y

        if (!hadPen) {
            hadPen = true
            if (strip.contains(pen.x)) {
                // A pinch that begins on the strip takes what it is over, and draws nothing.
                val taken = strip.select(strip.cellAt(pen.x, pen.y, aspect))
                if (taken?.kind == ToolStrip.Kind.UNDO) engine.undo()
                engine.colour = strip.colour
            } else {
                engine.begin(StrokeSource.AIR, pointFor(pen.x, pen.y))
                drawing = true
            }
            return
        }

        // A stroke that began on the canvas keeps drawing wherever the hand goes, including over
        // the strip. The strip can take a pinch; it can never take a mark already in progress.
        if (drawing) engine.extend(pointFor(pen.x, pen.y))
    }

    private fun pointFor(x: Float, y: Float) = AirDrawPoint(
        x = engine.viewport.toDocumentX(x * view.width),
        y = engine.viewport.toDocumentY(y * view.width),
        pressure = 1f,
        timeMs = System.currentTimeMillis()
    )

    /** Every visible hand gets a cursor, so "can it see me?" is answerable at a glance. */
    private fun cursorsFor(input: HandInput): List<AirDrawView.Cursor> = buildList {
        input.left?.let { add(AirDrawView.Cursor(it.x, it.y, hands.isPinching(AepHandHandedness.LEFT))) }
        input.right?.let { add(AirDrawView.Cursor(it.x, it.y, hands.isPinching(AepHandHandedness.RIGHT))) }
        input.unplaced?.let { add(AirDrawView.Cursor(it.x, it.y, hands.isLonePinching)) }
    }

    /**
     * Roughly once a second, what the hands are actually doing.
     *
     * The pinch thresholds were set before anyone had pinched at a television, and on hardware
     * they missed pinches that had certainly been made. Tuning them from a second guess would be
     * no better than the first, so this prints the numbers instead:
     *
     *     adb logcat -s AirDraw
     */
    private fun logInput(input: HandInput) {
        if (++frames % 15L != 0L) return
        Log.d(
            "AirDraw",
            "tracked=${input.tracked} hands=${input.handCount}" +
                " L=${format(hands.lastLeftPinch)} R=${format(hands.lastRightPinch)}" +
                " pinchL=${hands.isPinching(AepHandHandedness.LEFT)}" +
                " pinchR=${hands.isPinching(AepHandHandedness.RIGHT)}" +
                " bridging=${hands.isBridging}" +
                " raw=${format(hands.lastRawX)},${format(hands.lastRawY)}" +
                " reach=[${hands.reach}]" +
                " drawing=$drawing zoom=${zoom.isActive} scale=${"%.2f".format(engine.viewport.scale)}" +
                " strokes=${engine.drawing.all.size}"
        )
    }

    private fun format(value: Float?) = if (value == null) "--" else "%.2f".format(value)

    private companion object {
        /**
         * How far the pen may move while the tracker is blinking and still be the same stroke.
         *
         * In pointer units, so a tenth of the screen's width. Far enough to cover a hand that
         * kept moving through a dropout, short enough that a hand reacquired somewhere else
         * starts a new mark instead of ruling a line across the drawing.
         */
        const val REACQUIRE_JUMP = 0.10f
    }

    /**
     * Hand frames arrive through the generic path, because AEP wraps only pose and live person
     * ergonomically. That is not a limitation here: the generated parser turns a payload into a
     * typed model or returns null, so a `ready` event - which carries no frame - simply fails to
     * parse and is ignored without anyone having to test for it.
     */
    private fun onCapabilityEvent(event: AepCapabilityEvent) {
        if (event.capability != "camera.hand") return
        if (!view.streaming) runOnUiThread { view.streaming = true }
        val payload = AepPayloadJson.decode(event.payloadJson) ?: return
        AepHandFrame.fromMap(payload)?.let { latestHandFrame.set(it) }
    }

    override fun onResume() {
        super.onResume()
        if (::tvHost.isInitialized) tvHost.resume()
    }

    override fun onPause() {
        if (::tvHost.isInitialized) tvHost.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::tvHost.isInitialized) tvHost.stop()
        qrWorker.shutdownNow()
        view.recycleBitmaps()
        super.onDestroy()
    }
}
