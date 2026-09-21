package com.ambokit.aep.rockdodge

import com.ambokit.aep.core.ExperienceEngine
import com.ambokit.aep.core.PlayerAction
import com.ambokit.aep.core.PlayerMotionState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

data class RockDodgeConfig(
    val startingLives: Int = 3,
    val difficultyIntervalMs: Long = 10_000L,
    val speedMultiplierPerLevel: Float = 1.18f,
    val spawnIntervalMultiplierPerLevel: Float = 0.85f,
    val minimumSpawnDelayFloorMs: Long = 280L,
    val minimumSpawnEveryMs: Long = 720L,
    val maximumSpawnEveryMs: Long = 1_350L,
    val minimumRockSpeed: Float = 0.22f,
    val maximumRockSpeed: Float = 0.34f,
    val playerMinX: Float = 0.10f,
    val playerMaxX: Float = 0.90f,
    val playerY: Float = 0.82f,
    val playerHalfWidth: Float = 0.052f,
    val playerHalfHeight: Float = 0.105f,
    val rockRadiusMin: Float = 0.034f,
    val rockRadiusMax: Float = 0.058f,
    val jumpDurationMs: Long = 620L,
    val crouchDurationMs: Long = 620L,
    val spawnXMin: Float = 0.11f,
    val spawnXMax: Float = 0.89f,
    val offscreenY: Float = 1.04f,
    val jumpPlayerYOffset: Float = -0.11f,
    val crouchHeightMultiplier: Float = 0.48f,
    val crouchWidthMultiplier: Float = 1.12f
)

data class RockModel(
    val id: Long,
    val x: Float,
    val y: Float,
    val radius: Float,
    val rotationDegrees: Float,
    val visualSeed: Int
)

data class RockDodgeSnapshot(
    val running: Boolean,
    val completed: Boolean,
    val won: Boolean,
    val playerX: Float,
    val playerJumping: Boolean,
    val playerCrouching: Boolean,
    val lives: Int,
    val score: Int,
    val elapsedMs: Long,
    val difficultyLevel: Int,
    val rocks: List<RockModel>
)

class RockDodgeEngine(
    private val config: RockDodgeConfig = RockDodgeConfig(),
    private val random: Random = Random.Default
) : ExperienceEngine<RockDodgeSnapshot> {
    private data class MutableRock(
        val id: Long,
        val x: Float,
        var y: Float,
        val speed: Float,
        val radius: Float,
        var rotation: Float,
        val spinDegreesPerSecond: Float,
        val visualSeed: Int
    )

    private val rocks = mutableListOf<MutableRock>()
    private var nextRockId = 1L
    private var elapsedMs = 0L
    private var timeUntilNextSpawnMs = 900L
    private var jumpUntilMs = 0L
    private var crouchUntilMs = 0L
    private var running = false
    private var completed = false
    private var lives = config.startingLives
    private var playerX = 0.5f

    override fun start() {
        rocks.clear()
        nextRockId = 1L
        elapsedMs = 0L
        timeUntilNextSpawnMs = randomSpawnDelay(levelFor(0L))
        jumpUntilMs = 0L
        crouchUntilMs = 0L
        lives = config.startingLives
        playerX = 0.5f
        running = true
        completed = false
    }

    fun onPlayerMotion(motion: PlayerMotionState) {
        if (!running) return
        val normalized = (motion.horizontal + 1f) * 0.5f
        playerX = lerp(config.playerMinX, config.playerMaxX, normalized.coerceIn(0f, 1f))
        if (motion.jumping) jumpUntilMs = max(jumpUntilMs, elapsedMs + 120L)
        if (motion.crouching) crouchUntilMs = max(crouchUntilMs, elapsedMs + 120L)
    }

    override fun onPlayerAction(action: PlayerAction) {
        if (!running) return
        when (action) {
            PlayerAction.JUMP -> jumpUntilMs = elapsedMs + config.jumpDurationMs
            PlayerAction.CROUCH -> crouchUntilMs = elapsedMs + config.crouchDurationMs
            else -> Unit
        }
    }

    override fun tick(deltaMs: Long): RockDodgeSnapshot {
        if (!running || deltaMs <= 0) return snapshot()

        elapsedMs += deltaMs
        val level = levelFor(elapsedMs)
        timeUntilNextSpawnMs -= deltaMs
        while (timeUntilNextSpawnMs <= 0L) {
            spawnRock(level)
            timeUntilNextSpawnMs += randomSpawnDelay(level)
        }

        val seconds = deltaMs / 1000f
        rocks.forEach {
            it.y += it.speed * seconds
            it.rotation = (it.rotation + it.spinDegreesPerSecond * seconds) % 360f
        }

        val jumping = elapsedMs < jumpUntilMs
        val crouching = elapsedMs < crouchUntilMs
        val playerCenterY = if (jumping) config.playerY + config.jumpPlayerYOffset else config.playerY
        val playerHalfHeight = if (crouching) config.playerHalfHeight * config.crouchHeightMultiplier else config.playerHalfHeight
        val playerHalfWidth = if (crouching) config.playerHalfWidth * config.crouchWidthMultiplier else config.playerHalfWidth

        val iterator = rocks.iterator()
        while (iterator.hasNext()) {
            val rock = iterator.next()
            val hitX = abs(rock.x - playerX) <= rock.radius + playerHalfWidth
            val hitY = abs(rock.y - playerCenterY) <= rock.radius + playerHalfHeight
            if (hitX && hitY) {
                lives -= 1
                iterator.remove()
                if (lives <= 0) {
                    finish()
                    break
                }
                continue
            }
            if (rock.y - rock.radius > config.offscreenY) iterator.remove()
        }

        return snapshot()
    }

    override fun snapshot(): RockDodgeSnapshot = RockDodgeSnapshot(
        running = running,
        completed = completed,
        won = false,
        playerX = playerX,
        playerJumping = elapsedMs < jumpUntilMs,
        playerCrouching = elapsedMs < crouchUntilMs,
        lives = lives,
        score = (elapsedMs / 1000L).toInt(),
        elapsedMs = elapsedMs,
        difficultyLevel = levelFor(elapsedMs),
        rocks = rocks.map { RockModel(it.id, it.x, it.y, it.radius, it.rotation, it.visualSeed) }
    )

    private fun levelFor(ms: Long): Int = 1 + (ms / config.difficultyIntervalMs).toInt()

    private fun spawnRock(level: Int) {
        val x = random.nextFloat() * (config.spawnXMax - config.spawnXMin) + config.spawnXMin
        val radius = lerp(config.rockRadiusMin, config.rockRadiusMax, random.nextFloat())
        val baseSpeed = lerp(config.minimumRockSpeed, config.maximumRockSpeed, random.nextFloat())
        val speedMultiplier = config.speedMultiplierPerLevel.toDouble().pow((level - 1).toDouble()).toFloat()
        rocks += MutableRock(
            id = nextRockId++,
            x = x,
            y = -radius - random.nextFloat() * 0.08f,
            speed = baseSpeed * speedMultiplier,
            radius = radius,
            rotation = random.nextFloat() * 360f,
            spinDegreesPerSecond = random.nextFloat() * 70f - 35f,
            visualSeed = random.nextInt()
        )
    }

    private fun randomSpawnDelay(level: Int): Long {
        val multiplier = config.spawnIntervalMultiplierPerLevel.toDouble().pow((level - 1).toDouble())
        val min = max(config.minimumSpawnDelayFloorMs, (config.minimumSpawnEveryMs * multiplier).toLong())
        val max = max(min, (config.maximumSpawnEveryMs * multiplier).toLong())
        return if (max <= min) min else random.nextLong(min, max + 1)
    }

    private fun finish() {
        running = false
        completed = true
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
