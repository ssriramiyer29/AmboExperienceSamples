package com.ambokit.aep.airdraw.tv

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.AirDrawPoint
import com.ambokit.aep.airdraw.DominantHand
import com.ambokit.aep.airdraw.HandInterpreter
import com.ambokit.aep.airdraw.StrokeSource
import com.ambokit.aep.airdraw.ToolPanel
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
import java.util.concurrent.atomic.AtomicReference

/**
 * AirDraw: the TV is the canvas and your hand in the air is the pen.
 *
 * One capability, `camera.hand`, and two hands with different jobs. The drawing hand - the one
 * the player pinches with when asked, at the start - is the pen. The other hand opens and closes
 * the tool panel, and then the pen reaches over and picks from it.
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
    private val panel = ToolPanel()
    private val dominant = DominantHand()

    /**
     * Hand frames are conflated before they reach the UI thread, exactly as RockDodge conflates
     * pose. Posting every frame with runOnUiThread builds a backlog seconds deep while the stream
     * itself is current, and a drawing tool that renders a backlog draws the past.
     */
    private val latestHandFrame = AtomicReference<AepHandFrame?>(null)

    private var drawingWithAir = false

    /** Selection happens on the pinch closing, not while it is held. */
    private var penWasPinching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = AirDrawView(this, engine, panel)
        setContentView(view)
        engine.colour = panel.tools.colour
        engine.baseWidth = panel.tools.width

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
                        if (drawingWithAir) { engine.end(); drawingWithAir = false }
                        hands.reset()
                        panel.reset()
                        penWasPinching = false
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
            live.joinChanged += { join ->
                runOnUiThread {
                    view.joinUrl = join.url
                    view.joinQr = QrCodeBitmap.create(join.url)
                    view.invalidate()
                }
            }
            live.error += { error ->
                runOnUiThread { view.status = "${error.code}: ${error.message}"; view.invalidate() }
            }
        }

        tvHost = AndroidTvExperienceHost(session) { _ -> onFrame() }
        tvHost.start()
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
        val input = hands.read(frame, aspect)
        val now = System.currentTimeMillis()
        view.tracking = input.tracked

        val leftPinching = hands.isPinching(AepHandHandedness.LEFT)
        val rightPinching = hands.isPinching(AepHandHandedness.RIGHT)

        // Before anything else, the app has to know which hand is the pen. Asked once, with the
        // only input available at that point: a pinch.
        if (!dominant.isChosen) {
            if (dominant.observe(leftPinching, rightPinching, hands.isLonePinching)) {
                view.dominant = dominant.choice
            }
            view.cursor = null
            return
        }

        val penSide = dominant.choice
        // hand() falls back to a lone unplaced hand, which is the only hand it could be.
        val pen = input.hand(penSide)
        val offHand = input.otherThan(penSide)
        val penPinching = hands.isPinching(penSide) || (input.unplaced != null && hands.isLonePinching)
        val offPinching = if (penSide == AepHandHandedness.LEFT) rightPinching else leftPinching

        // The off hand's pinch opens and closes the tools. It is judged on release, so a zoom -
        // which is both hands pinching - does not open the panel on its way in.
        if (panel.observeOffHand(offPinching, penPinching)) {
            if (drawingWithAir) { engine.cancel(); drawingWithAir = false }
            zoom.end()
        }

        view.cursor = pen?.let { it.x to it.y }

        if (panel.isOpen) {
            view.hover = pen?.let { panel.cellAt(it.x, it.y, penSide, aspect) }
            // Select on the pinch closing rather than while it is held, or a hand resting over a
            // colour would pick it again on every frame.
            if (penPinching && !penWasPinching && pen != null) {
                val taken = panel.select(panel.cellAt(pen.x, pen.y, penSide, aspect), now)
                if (taken?.kind == ToolPanel.Kind.UNDO) engine.undo()
                engine.colour = panel.tools.colour
                engine.baseWidth = panel.tools.width
            }
            penWasPinching = penPinching
            return
        }

        view.hover = null
        penWasPinching = penPinching

        // Two pinching hands means zoom, not draw. Checked first, so starting a zoom never leaves
        // a stray mark where the second hand came in. There must actually be a second hand: one
        // hand cannot zoom, and treating it as though it could would cancel the stroke instead.
        val bothPinching = penPinching && offPinching && offHand != null
        val change = zoom.update(pen, offHand, bothPinching)
        if (change != null) {
            if (drawingWithAir) { engine.cancel(); drawingWithAir = false }
            engine.viewport.zoomAbout(change.focusX, change.focusY, change.scale)
            engine.viewport.panBy(change.panX, change.panY)
            return
        }
        if (bothPinching) {
            if (drawingWithAir) { engine.cancel(); drawingWithAir = false }
            return
        }

        if (pen == null) {
            if (drawingWithAir) { engine.end(); drawingWithAir = false }
            return
        }

        val point = AirDrawPoint(
            x = engine.viewport.toDocumentX(pen.x * view.width),
            y = engine.viewport.toDocumentY(pen.y * view.width),
            pressure = 1f,
            timeMs = now
        )
        when {
            penPinching && !drawingWithAir -> { engine.begin(StrokeSource.AIR, point); drawingWithAir = true }
            penPinching -> engine.extend(point)
            drawingWithAir -> { engine.end(); drawingWithAir = false }
        }
    }

    /**
     * Hand frames arrive through the generic path, because AEP wraps only pose and live person
     * ergonomically. That is not a limitation here: the generated parser turns a payload into a
     * typed model or returns null, so a `ready` event - which carries no frame - simply fails to
     * parse and is ignored without anyone having to test for it.
     */
    private fun onCapabilityEvent(event: AepCapabilityEvent) {
        if (event.capability != "camera.hand") return
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
        view.recycleBitmaps()
        super.onDestroy()
    }
}
