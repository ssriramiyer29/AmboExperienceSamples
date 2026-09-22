package com.ambokit.aep.rockdodge.tv

import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.ambokit.aep.core.AepConnectionState
import com.ambokit.aep.core.AepState
import com.ambokit.aep.core.AepJoinInfo
import com.ambokit.aep.core.AepPoseFrame
import com.ambokit.aep.core.PoseCharacterState
import com.ambokit.aep.core.PoseJoint
import com.ambokit.aep.rockdodge.RockDodgeSnapshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Production-style native Android TV renderer for Rock Dodge.
 *
 * Renderer only: gameplay, AEP semantics and AmboKit transport live elsewhere.
 * The visual target is a cinematic illustrated TV game rather than an app-like HUD.
 */
class RockDodgeView(context: Context) : View(context) {

    var snapshot: RockDodgeSnapshot? = null
    var joinInfo: AepJoinInfo? = null
    var experienceState: AepState = AepState.CREATED

    /**
     * Health of the link to the phone, which AEP reports separately from the experience state
     * (ADR-0002). Drawn over everything, including a running game: the moment it stops being
     * CONNECTED is exactly the moment the player is wondering why nothing is responding.
     */
    var connectionState: AepConnectionState = AepConnectionState.CONNECTING
    var statusText: String = "Starting AmboExperiencePlatform…"
    var playerBitmap: Bitmap? = null
    var poseFrame: AepPoseFrame? = null
    var poseCharacterState: PoseCharacterState? = null
    var lastLiveFrameAtMs: Long = 0L
    var joinQrBitmap: Bitmap? = null
    var onRestartRequested: (() -> Unit)? = null

    private val restartButtonRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rockPath = Path()
    private val backgroundBitmap: Bitmap? by lazy {
        runCatching { BitmapFactory.decodeResource(resources, R.drawable.rockdodge_volcano_world) }.getOrNull()
    }

    private val titleTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val bodyTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)


    init {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        drawWorld(canvas, w, h)

        val snap = snapshot
        if (snap == null || (!snap.running && !snap.completed)) {
            drawJoinAndCalibration(canvas, w, h)
            drawConnectionBanner(canvas, w, h)
            return
        }
        drawGame(canvas, w, h, snap)
        drawConnectionBanner(canvas, w, h)
    }

    /**
     * The one piece of UI that must survive every other screen.
     *
     * statusText is only drawn on the join/calibration screen, so before this existed a link that
     * dropped mid-game showed the player nothing at all: the rocks simply stopped responding and
     * the game looked broken rather than interrupted. Drawn last, so it sits above the game.
     */
    private fun drawConnectionBanner(canvas: Canvas, w: Float, h: Float) {
        val message = when (connectionState) {
            AepConnectionState.RECONNECTING -> "Reconnecting to your phone…"
            AepConnectionState.REJOIN_REQUIRED -> "Connection lost — scan the code again"
            AepConnectionState.CLOSED -> "Disconnected"
            else -> return
        }

        val bannerHeight = h * .072f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(232, 24, 16, 14)
        canvas.drawRect(0f, 0f, w, bannerHeight, paint)
        paint.color = Color.rgb(255, 168, 76)
        canvas.drawRect(0f, bannerHeight - h * .004f, w, bannerHeight, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = bodyTypeface
        paint.textSize = h * .026f
        paint.color = Color.rgb(255, 226, 196)
        canvas.drawText(message, w / 2f, bannerHeight * .64f, paint)
    }

    private fun drawWorld(canvas: Canvas, w: Float, h: Float) {
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        backgroundBitmap?.let { bmp ->
            val srcAspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
            val dstAspect = w / h.coerceAtLeast(1f)
            val src = if (srcAspect > dstAspect) {
                val cropW = (bmp.height * dstAspect).toInt()
                val left = (bmp.width - cropW) / 2
                Rect(left, 0, left + cropW, bmp.height)
            } else {
                val cropH = (bmp.width / dstAspect).toInt()
                val top = (bmp.height - cropH) / 2
                Rect(0, top, bmp.width, top + cropH)
            }
            canvas.drawBitmap(bmp, src, RectF(0f, 0f, w, h), paint)
        } ?: run {
            paint.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(67, 39, 35), Color.rgb(178, 102, 53), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // Cinematic grading: keep the erupting centre warm while protecting HUD/player readability.
        paint.shader = RadialGradient(
            w * .50f, h * .39f, min(w, h) * .72f,
            intArrayOf(Color.argb(0, 255, 143, 51), Color.argb(22, 110, 41, 21), Color.argb(105, 19, 12, 15)),
            floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Dark lower glaze gives the live person / pose a grounded stage without a UI panel.
        paint.shader = LinearGradient(0f, h * .58f, 0f, h, Color.TRANSPARENT, Color.argb(115, 18, 14, 13), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h * .58f, w, h, paint)
        paint.shader = null
    }

    private fun drawJoinAndCalibration(canvas: Canvas, w: Float, h: Float) {
        drawTopTitle(canvas, w, h, "ROCK DODGE")

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = bodyTypeface
        paint.textSize = h * .025f
        paint.color = Color.rgb(245, 226, 207)
        paint.setShadowLayer(8f, 0f, 2f, Color.BLACK)
        canvas.drawText(statusText, w / 2f, h * .18f, paint)
        paint.clearShadowLayer()

        val join = joinInfo
        if (join != null && experienceState != AepState.READY && experienceState != AepState.RUNNING) {
            joinQrBitmap?.let { qr ->
                val qrSize = min(w * .235f, h * .40f)
                val left = w / 2f - qrSize / 2f
                val top = h * .255f
                paint.color = Color.argb(238, 255, 247, 236)
                canvas.drawRoundRect(RectF(left - 18f, top - 18f, left + qrSize + 18f, top + qrSize + 18f), 22f, 22f, paint)
                canvas.drawBitmap(qr, null, RectF(left, top, left + qrSize, top + qrSize), paint)
            }
            paint.typeface = titleTypeface
            paint.textSize = h * .026f
            paint.color = Color.WHITE
            canvas.drawText("SCAN WITH AMBO COMPANION", w / 2f, h * .76f, paint)
            paint.typeface = bodyTypeface
            paint.textSize = h * .014f
            paint.color = Color.rgb(225, 204, 184)
            drawWrappedText(canvas, join.url, w / 2f, h * .81f, w * .76f, h * .019f)
        } else {
            // Calibration uses the real visualization so there is no visual discontinuity at game start.
            poseCharacterState?.takeIf { it.visible }?.let { state ->
                drawPoseCharacter(canvas, state, w * .50f, h * .91f, h * .42f)
            }
            paint.typeface = titleTypeface
            paint.textSize = h * .029f
            paint.color = Color.WHITE
            canvas.drawText("STAND NATURALLY IN VIEW", w / 2f, h * .82f, paint)
            paint.typeface = bodyTypeface
            paint.textSize = h * .021f
            paint.color = Color.rgb(236, 213, 193)
            canvas.drawText("Hold still for a moment while AEP calibrates your movement", w / 2f, h * .865f, paint)
        }
    }

    private fun drawGame(canvas: Canvas, w: Float, h: Float, snap: RockDodgeSnapshot) {
        drawTopTitle(canvas, w, h, "EVADE THE VOLCANIC RAIN!")
        drawGameplayCharacterCover(canvas, w, h)
        drawHud(canvas, w, h, snap)

        snap.rocks.forEach { rock ->
            val x = rock.x * w
            val y = h * (.02f + rock.y * .91f)
            val r = rock.radius * min(w, h) * 1.72f
            drawLavaRock(canvas, x, y, r, rock.rotationDegrees, rock.visualSeed)
        }

        val playerX = snap.playerX * w
        val groundY = h * .93f
        val jumpOffset = if (snap.playerJumping) h * .14f else 0f
        val playerBottom = groundY - jumpOffset
        val targetHeight = h * .55f

        // Live mode is visual-only and considered active only while fresh livevideo.person
        // frames are arriving. Private Pose never reuses a stale player bitmap.
        val now = System.currentTimeMillis()
        val livePerson = playerBitmap?.takeIf { !it.isRecycled && now - lastLiveFrameAtMs <= 1_250L }
        if (livePerson != null) {
            drawLivePerson(canvas, livePerson, playerX, playerBottom, targetHeight)
        } else {
            poseCharacterState?.takeIf { it.visible }?.let { state ->
                drawPoseCharacter(canvas, state, playerX, playerBottom, h * .43f)
            } ?: drawFallbackPlayer(canvas, playerX, playerBottom, h * .43f)
        }

        if (snap.completed) drawResultsOverlay(canvas, w, h, snap) else restartButtonRect.setEmpty()
    }

    private fun drawGameplayCharacterCover(canvas: Canvas, w: Float, h: Float) {
        // The supplied concept background contains a static explorer. During gameplay we cover that
        // illustration with a natural smoke/dust bank so the active AEP player is the only character.
        val cx = w * .515f
        val cy = h * .625f
        val cloud = arrayOf(
            floatArrayOf(-.085f,-.035f,.105f,.095f), floatArrayOf(-.025f,-.075f,.115f,.115f),
            floatArrayOf(.055f,-.035f,.100f,.090f), floatArrayOf(-.055f,.035f,.120f,.095f),
            floatArrayOf(.040f,.050f,.115f,.090f)
        )
        cloud.forEachIndexed { i, c ->
            paint.color = if (i % 2 == 0) Color.argb(225, 89, 72, 62) else Color.argb(220, 112, 89, 66)
            canvas.drawOval(RectF(cx + c[0]*w, cy + c[1]*h, cx + c[2]*w, cy + c[3]*h), paint)
        }
        paint.shader = LinearGradient(cx, cy, cx, cy + h*.13f,
            Color.argb(210, 128, 102, 58), Color.argb(235, 83, 73, 42), Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(cx-w*.10f, cy+h*.03f, cx+w*.12f, cy+h*.15f), paint)
        paint.shader = null
    }

    private fun drawTopTitle(canvas: Canvas, w: Float, h: Float, title: String) {
        paint.typeface = titleTypeface
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = h * .047f
        paint.color = Color.rgb(255, 233, 211)
        paint.setShadowLayer(10f, 0f, 3f, Color.argb(210, 28, 11, 7))
        canvas.drawText(title, w / 2f, h * .075f, paint)
        paint.clearShadowLayer()
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float, snap: RockDodgeSnapshot) {
        // Minimal cinematic HUD: no large rectangular cards competing with the world.
        paint.typeface = titleTypeface
        paint.setShadowLayer(8f, 0f, 2f, Color.argb(220, 25, 10, 7))

        paint.textAlign = Paint.Align.LEFT
        paint.textSize = h * .034f
        paint.color = Color.rgb(255, 101, 72)
        canvas.drawText("♥".repeat(snap.lives.coerceAtLeast(0)), w * .035f, h * .14f, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = h * .028f
        paint.color = Color.WHITE
        canvas.drawText("SCORE  ${snap.score}", w * .965f, h * .125f, paint)
        paint.textSize = h * .024f
        paint.color = Color.rgb(255, 219, 171)
        canvas.drawText("${snap.score}s · LV ${snap.difficultyLevel}", w * .965f, h * .162f, paint)
        paint.clearShadowLayer()
    }

    private fun drawLivePerson(canvas: Canvas, bitmap: Bitmap, centerX: Float, bottomY: Float, targetHeight: Float) {
        val sourceW = bitmap.width.coerceAtLeast(1).toFloat()
        val sourceH = bitmap.height.coerceAtLeast(1).toFloat()
        val aspect = sourceW / sourceH
        val drawH = targetHeight
        val drawW = (drawH * aspect).coerceAtMost(width * .46f)
        val dst = RectF(centerX - drawW / 2f, bottomY - drawH, centerX + drawW / 2f, bottomY)

        // Ground shadow + warm rim light integrates the cutout into the illustrated world.
        paint.shader = RadialGradient(centerX, bottomY - drawH * .05f, drawW * .72f,
            intArrayOf(Color.argb(105, 0, 0, 0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(centerX - drawW * .72f, bottomY - drawH * .10f, centerX + drawW * .72f, bottomY + drawH * .055f), paint)
        paint.shader = null

        paint.colorFilter = PorterDuffColorFilter(Color.argb(85, 255, 137, 44), PorterDuff.Mode.SRC_ATOP)
        paint.alpha = 130
        val rim = RectF(dst.left - 5f, dst.top - 5f, dst.right + 5f, dst.bottom + 3f)
        canvas.drawBitmap(bitmap, null, rim, paint)
        paint.colorFilter = null
        paint.alpha = 255
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    private fun drawPoseCharacter(
        canvas: Canvas,
        state: PoseCharacterState,
        anchorX: Float,
        bottomY: Float,
        targetHeight: Float
    ) {
        if (!state.visible) {
            drawFallbackPlayer(canvas, anchorX, bottomY, targetHeight)
            return
        }

        // Renderer scale is fixed for the whole session. PoseRigMapper already normalized
        // against a slowly changing anatomy metric, so crouching/raising arms never resizes
        // the whole avatar. Anchor feet to the game ground; game motion owns translation.
        val leftAnkleRaw = state[PoseJoint.LEFT_ANKLE]
        val rightAnkleRaw = state[PoseJoint.RIGHT_ANKLE]
        val ankleY = listOfNotNull(leftAnkleRaw?.y, rightAnkleRaw?.y).average().toFloat().takeIf { !it.isNaN() } ?: 1.55f
        val pixelsPerBodyUnit = targetHeight / 4.25f

        fun p(joint: PoseJoint): PointF? {
            val point = state[joint] ?: return null
            return PointF(
                anchorX + point.x * pixelsPerBodyUnit,
                bottomY + (point.y - ankleY) * pixelsPerBodyUnit
            )
        }

        val nose = p(PoseJoint.NOSE)
        val ls = p(PoseJoint.LEFT_SHOULDER); val rs = p(PoseJoint.RIGHT_SHOULDER)
        val le = p(PoseJoint.LEFT_ELBOW); val re = p(PoseJoint.RIGHT_ELBOW)
        val lw = p(PoseJoint.LEFT_WRIST); val rw = p(PoseJoint.RIGHT_WRIST)
        val lh = p(PoseJoint.LEFT_HIP); val rh = p(PoseJoint.RIGHT_HIP)
        val lk = p(PoseJoint.LEFT_KNEE); val rk = p(PoseJoint.RIGHT_KNEE)
        val la = p(PoseJoint.LEFT_ANKLE); val ra = p(PoseJoint.RIGHT_ANKLE)

        fun bone(a: PointF?, b: PointF?, bodyColor: Int, width: Float) {
            if (a == null || b == null) return
            // Dark comic outline, warm body segment, then a slim teal pose-rig highlight.
            strokePaint.color = Color.rgb(54, 35, 28)
            strokePaint.strokeWidth = width * 1.38f
            canvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
            strokePaint.color = bodyColor
            strokePaint.strokeWidth = width
            canvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
            strokePaint.color = Color.argb(195, 87, 206, 184)
            strokePaint.strokeWidth = maxOf(3f, width * .16f)
            canvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
        }

        fun joint(point: PointF?, fill: Int, radius: Float) {
            if (point == null) return
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(49, 32, 26)
            canvas.drawCircle(point.x, point.y, radius * 1.35f, paint)
            paint.color = fill
            canvas.drawCircle(point.x, point.y, radius, paint)
            paint.color = Color.argb(210, 255, 241, 209)
            canvas.drawCircle(point.x - radius * .24f, point.y - radius * .24f, radius * .20f, paint)
        }

        // Grounding shadow.
        val shadowW = targetHeight * .23f
        paint.shader = RadialGradient(anchorX, bottomY, shadowW,
            intArrayOf(Color.argb(110, 0, 0, 0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(anchorX-shadowW, bottomY-targetHeight*.025f, anchorX+shadowW, bottomY+targetHeight*.035f), paint)
        paint.shader = null

        val shirt = Color.rgb(217, 185, 123)
        val skin = Color.rgb(225, 154, 105)
        val shorts = Color.rgb(116, 83, 50)
        val boots = Color.rgb(83, 53, 36)
        val scarf = Color.rgb(185, 58, 42)
        val limb = targetHeight * .052f

        // Legs and boots remain articulated from the landmark rig.
        bone(lh, lk, shorts, limb * 1.03f)
        bone(rh, rk, shorts, limb * 1.03f)
        bone(lk, la, boots, limb * .92f)
        bone(rk, ra, boots, limb * .92f)

        // Torso: a compact explorer vest around the pose rig, never a large static block.
        if (ls != null && rs != null && lh != null && rh != null) {
            val torso = Path().apply {
                moveTo(ls.x, ls.y); lineTo(rs.x, rs.y); lineTo(rh.x, rh.y); lineTo(lh.x, lh.y); close()
            }
            paint.color = Color.rgb(58, 39, 29)
            canvas.drawPath(torso, paint)
            val inset = targetHeight * .010f
            val inner = Path().apply {
                moveTo(ls.x + inset, ls.y + inset); lineTo(rs.x - inset, rs.y + inset)
                lineTo(rh.x - inset*.6f, rh.y - inset); lineTo(lh.x + inset*.6f, lh.y - inset); close()
            }
            paint.color = shirt
            canvas.drawPath(inner, paint)
            strokePaint.color = Color.argb(210, 87, 206, 184)
            strokePaint.strokeWidth = maxOf(3f, targetHeight * .008f)
            canvas.drawLine((ls.x+rs.x)/2f, (ls.y+rs.y)/2f, (lh.x+rh.x)/2f, (lh.y+rh.y)/2f, strokePaint)
            strokePaint.color = scarf
            strokePaint.strokeWidth = targetHeight * .025f
            canvas.drawLine(ls.x, ls.y + targetHeight*.018f, rs.x, rs.y + targetHeight*.018f, strokePaint)
        }

        bone(ls, le, shirt, limb)
        bone(rs, re, shirt, limb)
        bone(le, lw, skin, limb * .78f)
        bone(re, rw, skin, limb * .78f)

        // Explorer head/helmet follows the head landmark but stays visually compact.
        nose?.let { n ->
            val r = targetHeight * .058f
            paint.color = Color.rgb(55, 36, 28); canvas.drawCircle(n.x, n.y, r*1.18f, paint)
            paint.color = skin; canvas.drawCircle(n.x, n.y + r*.08f, r, paint)
            paint.color = Color.rgb(102, 73, 42)
            canvas.drawArc(RectF(n.x-r*1.05f, n.y-r*1.12f, n.x+r*1.05f, n.y+r*.42f), 182f, 176f, true, paint)
            // Head lamp.
            paint.setShadowLayer(r*.55f, 0f, 0f, Color.rgb(255, 177, 59))
            paint.color = Color.rgb(255, 232, 137)
            canvas.drawCircle(n.x+r*.68f, n.y-r*.66f, r*.22f, paint)
            paint.clearShadowLayer()
            joint(n, Color.rgb(255, 159, 64), targetHeight*.013f)
        }

        // Landmark nodes deliberately remain visible, like an illustrated pose puppet.
        joint(ls, Color.rgb(69, 180, 92), targetHeight*.012f); joint(rs, Color.rgb(69, 180, 92), targetHeight*.012f)
        joint(le, Color.rgb(55, 126, 220), targetHeight*.012f); joint(re, Color.rgb(55, 126, 220), targetHeight*.012f)
        joint(lw, Color.rgb(221, 71, 66), targetHeight*.012f); joint(rw, Color.rgb(221, 71, 66), targetHeight*.012f)
        joint(lh, Color.rgb(139, 105, 190), targetHeight*.012f); joint(rh, Color.rgb(139, 105, 190), targetHeight*.012f)
        joint(lk, Color.rgb(239, 198, 73), targetHeight*.012f); joint(rk, Color.rgb(239, 198, 73), targetHeight*.012f)
        joint(la, Color.rgb(96, 202, 168), targetHeight*.012f); joint(ra, Color.rgb(96, 202, 168), targetHeight*.012f)
    }

    private fun drawFallbackPlayer(canvas: Canvas, centerX: Float, bottomY: Float, targetHeight: Float) {
        paint.color = Color.rgb(255, 193, 88)
        val headR = targetHeight * .085f
        canvas.drawCircle(centerX, bottomY - targetHeight * .86f, headR, paint)
        strokePaint.color = paint.color
        strokePaint.strokeWidth = targetHeight * .05f
        canvas.drawLine(centerX, bottomY - targetHeight * .75f, centerX, bottomY - targetHeight * .34f, strokePaint)
        canvas.drawLine(centerX, bottomY - targetHeight * .64f, centerX - targetHeight * .18f, bottomY - targetHeight * .48f, strokePaint)
        canvas.drawLine(centerX, bottomY - targetHeight * .64f, centerX + targetHeight * .18f, bottomY - targetHeight * .48f, strokePaint)
        canvas.drawLine(centerX, bottomY - targetHeight * .34f, centerX - targetHeight * .14f, bottomY, strokePaint)
        canvas.drawLine(centerX, bottomY - targetHeight * .34f, centerX + targetHeight * .14f, bottomY, strokePaint)
    }

    private fun drawLavaRock(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotation: Float, seed: Int) {
        val rng = Random(seed)
        val count = 10
        rockPath.reset()
        for (i in 0 until count) {
            val angle = rotation * PI / 180.0 + i * (2.0 * PI / count)
            val rr = radius * (.74f + rng.nextFloat() * .36f)
            val x = cx + cos(angle).toFloat() * rr
            val y = cy + sin(angle).toFloat() * rr
            if (i == 0) rockPath.moveTo(x, y) else rockPath.lineTo(x, y)
        }
        rockPath.close()

        // Motion/heat trail.
        paint.shader = LinearGradient(cx, cy - radius * 2.1f, cx, cy + radius,
            intArrayOf(Color.argb(0, 255, 102, 33), Color.argb(100, 255, 86, 24), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(cx - radius * .42f, cy - radius * 2.0f, cx + radius * .42f, cy + radius * .45f), paint)
        paint.shader = null

        // Outer incandescent glow.
        paint.shader = RadialGradient(cx, cy, radius * 1.55f,
            intArrayOf(Color.argb(115, 255, 91, 29), Color.argb(28, 255, 70, 20), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.55f, paint)
        paint.shader = null

        paint.shader = RadialGradient(cx - radius * .25f, cy - radius * .32f, radius * 1.28f,
            intArrayOf(Color.rgb(95, 48, 36), Color.rgb(59, 34, 30), Color.rgb(30, 25, 25)),
            floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(rockPath, paint)
        paint.shader = null

        // Lava fissures.
        val crackPaint = strokePaint
        crackPaint.color = Color.rgb(255, 88, 35)
        crackPaint.strokeWidth = radius * .085f
        crackPaint.setShadowLayer(radius * .10f, 0f, 0f, Color.rgb(255, 110, 33))
        val a = rotation * PI / 180.0
        val p1x = cx - cos(a).toFloat() * radius * .48f
        val p1y = cy - sin(a).toFloat() * radius * .48f
        val midx = cx + cos(a + .75).toFloat() * radius * .10f
        val midy = cy + sin(a + .75).toFloat() * radius * .10f
        val p2x = cx + cos(a + .30).toFloat() * radius * .48f
        val p2y = cy + sin(a + .30).toFloat() * radius * .48f
        canvas.drawLine(p1x, p1y, midx, midy, crackPaint)
        canvas.drawLine(midx, midy, p2x, p2y, crackPaint)
        crackPaint.clearShadowLayer()
    }

    private fun drawResultsOverlay(canvas: Canvas, w: Float, h: Float, snap: RockDodgeSnapshot) {
        paint.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(115, 20, 8, 7), Color.argb(225, 17, 9, 8), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = titleTypeface
        paint.color = Color.rgb(255, 232, 204)
        paint.textSize = h * .079f
        paint.setShadowLayer(12f, 0f, 4f, Color.BLACK)
        canvas.drawText("THE VOLCANO GOT YOU", w / 2f, h * .37f, paint)
        paint.clearShadowLayer()
        paint.textSize = h * .034f
        paint.color = Color.WHITE
        canvas.drawText("SURVIVED  ${snap.score}s   ·   LEVEL ${snap.difficultyLevel}", w / 2f, h * .45f, paint)

        val buttonW = w * .25f
        val buttonH = h * .092f
        restartButtonRect.set(w / 2f - buttonW / 2f, h * .55f, w / 2f + buttonW / 2f, h * .55f + buttonH)
        paint.shader = LinearGradient(restartButtonRect.left, restartButtonRect.top, restartButtonRect.right, restartButtonRect.bottom,
            Color.rgb(236, 84, 34), Color.rgb(169, 44, 23), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(restartButtonRect, 24f, 24f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.textSize = h * .031f
        canvas.drawText("RESTART GAME", w / 2f, restartButtonRect.centerY() + h * .011f, paint)
        paint.typeface = bodyTypeface
        paint.color = Color.rgb(235, 207, 183)
        paint.textSize = h * .019f
        canvas.drawText("Press OK · your Companion stays connected", w / 2f, h * .72f, paint)
    }

    private fun drawWrappedText(canvas: Canvas, text: String, centerX: Float, startY: Float, maxWidth: Float, lineHeight: Float) {
        val lines = mutableListOf<String>(); var chunk = ""
        for (ch in text) {
            val candidate = chunk + ch
            if (paint.measureText(candidate) > maxWidth && chunk.isNotEmpty()) { lines += chunk; chunk = ch.toString() } else chunk = candidate
        }
        if (chunk.isNotEmpty()) lines += chunk
        lines.take(3).forEachIndexed { index, line -> canvas.drawText(line, centerX, startY + index * lineHeight, paint) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (snapshot?.completed == true && keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)) {
            onRestartRequested?.invoke(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (snapshot?.completed == true && event.action == MotionEvent.ACTION_UP && restartButtonRect.contains(event.x, event.y)) {
            onRestartRequested?.invoke(); return true
        }
        return true
    }
}
