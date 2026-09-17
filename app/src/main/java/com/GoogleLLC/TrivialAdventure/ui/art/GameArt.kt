package com.GoogleLLC.TrivialAdventure.ui.art

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.GoogleLLC.TrivialAdventure.game.Enemy
import com.GoogleLLC.TrivialAdventure.ui.theme.BladeSilver
import com.GoogleLLC.TrivialAdventure.ui.theme.EmberLight
import com.GoogleLLC.TrivialAdventure.ui.theme.EmberRed
import com.GoogleLLC.TrivialAdventure.ui.theme.GripBrown
import com.GoogleLLC.TrivialAdventure.ui.theme.RuneGold
import com.GoogleLLC.TrivialAdventure.ui.theme.VaultDeep
import com.GoogleLLC.TrivialAdventure.ui.theme.VaultNight
import com.GoogleLLC.TrivialAdventure.ui.theme.VaultPanel
import com.GoogleLLC.TrivialAdventure.ui.theme.XpMint

// -------------------------------------------------------------------------------------------
// Creature tints.
//
// The launcher icon only carries five hues, which is not enough to tell eight creatures apart.
// These are all desaturated and pulled toward the icon's purple so they still read as the same
// world. Every creature's eyes are RuneGold, which is the strongest tie back to the icon.
// -------------------------------------------------------------------------------------------
private val GoblinMoss = Color(0xFF8FBF6B)
private val RatFur = Color(0xFF9A8AA8)
private val BanditCloth = Color(0xFF7A5CA8)
private val WispGlow = Color(0xFFBFA6FF)
private val CrabShell = Color(0xFFC9705A)
private val ChickenCream = Color(0xFFEDE3FF)
private val SkinTan = Color(0xFFE8C9A0)

/** Drawn at a fixed 100x100 logical box, then scaled by the caller's Modifier.size(). */
@Composable
fun EnemySprite(enemy: Enemy, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (enemy) {
            Enemy.SLIME -> drawSlime()
            Enemy.GOBLIN -> drawGoblin()
            Enemy.RAT -> drawRat()
            Enemy.BANDIT -> drawBandit()
            Enemy.WISP -> drawWisp()
            Enemy.SKELETON -> drawSkeleton()
            Enemy.CRAB -> drawCrab()
            Enemy.CHICKEN -> drawChicken()
        }
    }
}

@Composable
fun BossSprite(modifier: Modifier = Modifier) {
    Canvas(modifier) { drawBoss() }
}

/**
 * The stage the fight happens on: night sky, stars, a lit floor disc, and the creature.
 *
 * @param encounterKey changes whenever a new fight happens, driving the crossfade.
 * @param enemy `null` renders the boss instead.
 */
@Composable
fun SceneStage(
    encounterKey: Long,
    enemy: Enemy?,
    isBoss: Boolean,
    modifier: Modifier = Modifier,
) {
    // Slow vertical bob so the scene is not completely static between taps.
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bobOffset",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawStageBackdrop(isBoss) }

        Crossfade(
            targetState = encounterKey to (enemy to isBoss),
            label = "encounter",
        ) { (_, who) ->
            val (which, boss) = who
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Bottom-aligned, not centred: creature art is drawn with its feet near the
                    // bottom of its own canvas, so centring leaves it hovering above the disc.
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                when {
                    boss -> BossSprite(
                        Modifier
                            .size(132.dp)
                            .offset(y = bob.dp),
                    )

                    which != null -> EnemySprite(
                        enemy = which,
                        modifier = Modifier
                            .size(104.dp)
                            .offset(y = bob.dp),
                    )

                    // Before the first quest: just the empty road.
                    else -> Canvas(Modifier.size(104.dp)) { drawSignpost() }
                }
            }
        }
    }
}

// ===========================================================================================
// Backdrop
// ===========================================================================================

/** Deterministic star field - a random one would twinkle chaotically on every recomposition. */
private val STARS = listOf(
    0.08f to 0.18f, 0.21f to 0.42f, 0.33f to 0.12f, 0.47f to 0.30f,
    0.58f to 0.15f, 0.71f to 0.38f, 0.83f to 0.20f, 0.92f to 0.46f,
    0.14f to 0.62f, 0.89f to 0.66f,
)

private fun DrawScope.drawStageBackdrop(isBoss: Boolean) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = if (isBoss) {
                listOf(Color(0xFF2A0F1F), VaultDeep)
            } else {
                listOf(VaultDeep, VaultNight)
            },
        ),
    )

    STARS.forEach { (fx, fy) ->
        drawCircle(
            color = BladeSilver.copy(alpha = 0.35f),
            radius = size.minDimension * 0.006f,
            center = Offset(size.width * fx, size.height * fy),
        )
    }

    // Lit floor disc the creature stands on.
    val discW = size.width * 0.62f
    val discH = size.height * 0.16f
    drawOval(
        color = if (isBoss) EmberRed.copy(alpha = 0.18f) else VaultPanel,
        topLeft = Offset((size.width - discW) / 2f, size.height * 0.80f),
        size = Size(discW, discH),
    )
}

private fun DrawScope.drawSignpost() {
    val s = size.minDimension
    drawLine(
        color = GripBrown,
        start = Offset(size.width * 0.5f, size.height * 0.30f),
        end = Offset(size.width * 0.5f, size.height * 0.92f),
        strokeWidth = s * 0.06f,
        cap = StrokeCap.Round,
    )
    drawRoundRect(
        color = RuneGold,
        topLeft = Offset(size.width * 0.18f, size.height * 0.26f),
        size = Size(s * 0.64f, s * 0.22f),
        cornerRadius = CornerRadius(s * 0.05f),
    )
    // "→" scratched into the sign.
    drawLine(
        color = VaultNight,
        start = Offset(size.width * 0.30f, size.height * 0.37f),
        end = Offset(size.width * 0.68f, size.height * 0.37f),
        strokeWidth = s * 0.035f,
        cap = StrokeCap.Round,
    )
}

// ===========================================================================================
// Creatures
//
// All coordinates are fractions of the canvas, so every sprite scales cleanly.
// ===========================================================================================

/** Two gold eyes - the recurring motif that ties every creature to the icon. */
private fun DrawScope.goldEyes(lx: Float, rx: Float, y: Float, r: Float) {
    drawCircle(RuneGold, size.minDimension * r, Offset(size.width * lx, size.height * y))
    drawCircle(RuneGold, size.minDimension * r, Offset(size.width * rx, size.height * y))
}

private fun DrawScope.drawSlime() {
    val w = size.width
    val h = size.height
    val dome = Path().apply {
        moveTo(w * 0.12f, h * 0.86f)
        cubicTo(w * 0.10f, h * 0.26f, w * 0.90f, h * 0.26f, w * 0.88f, h * 0.86f)
        close()
    }
    drawPath(dome, XpMint)
    // Glossy highlight.
    drawOval(
        color = Color.White.copy(alpha = 0.45f),
        topLeft = Offset(w * 0.26f, h * 0.40f),
        size = Size(w * 0.18f, h * 0.10f),
    )
    goldEyes(lx = 0.40f, rx = 0.62f, y = 0.62f, r = 0.045f)
}

private fun DrawScope.drawGoblin() {
    val w = size.width
    val h = size.height
    val s = size.minDimension

    // Pointed ears.
    listOf(
        Triple(0.14f, 0.34f, 0.36f),   // left: tipX, baseX, ...
        Triple(0.86f, 0.66f, 0.36f),   // right
    ).forEach { (tipX, baseX, topY) ->
        drawPath(
            Path().apply {
                moveTo(w * tipX, h * topY)
                lineTo(w * baseX, h * 0.42f)
                lineTo(w * baseX, h * 0.62f)
                close()
            },
            GoblinMoss,
        )
    }

    drawCircle(GoblinMoss, s * 0.24f, Offset(w * 0.5f, h * 0.54f))
    goldEyes(lx = 0.42f, rx = 0.58f, y = 0.50f, r = 0.038f)
    // Grin.
    drawLine(
        color = VaultDeep,
        start = Offset(w * 0.42f, h * 0.65f),
        end = Offset(w * 0.58f, h * 0.65f),
        strokeWidth = s * 0.03f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawRat() {
    val w = size.width
    val h = size.height
    val s = size.minDimension

    // Tail first, so the body overlaps it.
    drawPath(
        Path().apply {
            moveTo(w * 0.76f, h * 0.70f)
            cubicTo(w * 0.98f, h * 0.68f, w * 0.96f, h * 0.40f, w * 0.82f, h * 0.38f)
        },
        color = RatFur,
        style = Stroke(width = s * 0.035f, cap = StrokeCap.Round),
    )
    drawOval(
        color = RatFur,
        topLeft = Offset(w * 0.34f, h * 0.52f),
        size = Size(w * 0.46f, h * 0.30f),
    )
    drawCircle(RatFur, s * 0.10f, Offset(w * 0.30f, h * 0.50f))  // ear
    drawCircle(RatFur, s * 0.15f, Offset(w * 0.30f, h * 0.64f))  // head
    drawCircle(RuneGold, s * 0.03f, Offset(w * 0.25f, h * 0.62f))
    drawCircle(EmberLight, s * 0.025f, Offset(w * 0.17f, h * 0.68f)) // nose
}

private fun DrawScope.drawBandit() {
    val w = size.width
    val h = size.height
    val s = size.minDimension

    drawCircle(SkinTan, s * 0.21f, Offset(w * 0.5f, h * 0.58f))
    // Hat: dome + brim.
    drawPath(
        Path().apply {
            moveTo(w * 0.30f, h * 0.44f)
            cubicTo(w * 0.32f, h * 0.24f, w * 0.68f, h * 0.24f, w * 0.70f, h * 0.44f)
            close()
        },
        BanditCloth,
    )
    drawRoundRect(
        color = BanditCloth,
        topLeft = Offset(w * 0.20f, h * 0.42f),
        size = Size(w * 0.60f, h * 0.07f),
        cornerRadius = CornerRadius(s * 0.035f),
    )
    // Mask band.
    drawRoundRect(
        color = VaultDeep,
        topLeft = Offset(w * 0.29f, h * 0.52f),
        size = Size(w * 0.42f, h * 0.10f),
        cornerRadius = CornerRadius(s * 0.02f),
    )
    goldEyes(lx = 0.42f, rx = 0.58f, y = 0.57f, r = 0.03f)
}

private fun DrawScope.drawWisp() {
    val w = size.width
    val h = size.height
    val s = size.minDimension

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(WispGlow.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.52f),
            radius = s * 0.42f,
        ),
        radius = s * 0.42f,
        center = Offset(w * 0.5f, h * 0.52f),
    )
    drawCircle(BladeSilver, s * 0.13f, Offset(w * 0.5f, h * 0.52f))
    drawCircle(XpMint, s * 0.06f, Offset(w * 0.5f, h * 0.52f))
    // Orbiting motes.
    listOf(0.22f to 0.34f, 0.78f to 0.44f, 0.60f to 0.78f).forEach { (fx, fy) ->
        drawCircle(WispGlow, s * 0.035f, Offset(w * fx, h * fy))
    }
}

private fun DrawScope.drawSkeleton() {
    val w = size.width
    val h = size.height
    val s = size.minDimension

    drawRoundRect(
        color = BladeSilver,
        topLeft = Offset(w * 0.30f, h * 0.28f),
        size = Size(w * 0.40f, h * 0.36f),
        cornerRadius = CornerRadius(s * 0.13f),
    )
    // Jaw.
    drawRoundRect(
        color = BladeSilver,
        topLeft = Offset(w * 0.37f, h * 0.62f),
        size = Size(w * 0.26f, h * 0.12f),
        cornerRadius = CornerRadius(s * 0.03f),
    )
    // Sockets, with an ember burning inside.
    listOf(0.42f, 0.58f).forEach { fx ->
        drawCircle(VaultDeep, s * 0.072f, Offset(w * fx, h * 0.44f))
        drawCircle(EmberLight, s * 0.03f, Offset(w * fx, h * 0.44f))
    }
    // Teeth.
    listOf(0.44f, 0.50f, 0.56f).forEach { fx ->
        drawLine(
            color = VaultDeep,
            start = Offset(w * fx, h * 0.62f),
            end = Offset(w * fx, h * 0.74f),
            strokeWidth = s * 0.018f,
        )
    }
}

private fun DrawScope.drawCrab() {
    val w = size.width
    val h = size.height
    val s = size.minDimension

    // Legs.
    listOf(-1f, 1f).forEach { dir ->
        listOf(0.60f, 0.70f, 0.80f).forEach { fy ->
            drawLine(
                color = CrabShell,
                start = Offset(w * (0.5f + dir * 0.16f), h * fy),
                end = Offset(w * (0.5f + dir * 0.36f), h * (fy + 0.08f)),
                strokeWidth = s * 0.03f,
                cap = StrokeCap.Round,
            )
        }
    }
    // Claws.
    listOf(0.18f, 0.82f).forEach { fx ->
        drawCircle(CrabShell, s * 0.11f, Offset(w * fx, h * 0.48f))
    }
    // Shell.
    drawOval(
        color = CrabShell,
        topLeft = Offset(w * 0.26f, h * 0.50f),
        size = Size(w * 0.48f, h * 0.30f),
    )
    // Eyestalks.
    listOf(0.42f, 0.58f).forEach { fx ->
        drawLine(
            color = CrabShell,
            start = Offset(w * fx, h * 0.52f),
            end = Offset(w * fx, h * 0.38f),
            strokeWidth = s * 0.025f,
            cap = StrokeCap.Round,
        )
        drawCircle(RuneGold, s * 0.04f, Offset(w * fx, h * 0.36f))
    }
}

private fun DrawScope.drawChicken() {
    val w = size.width
    val h = size.height
    val s = size.minDimension

    // Legs.
    listOf(0.46f, 0.60f).forEach { fx ->
        drawLine(
            color = RuneGold,
            start = Offset(w * fx, h * 0.78f),
            end = Offset(w * fx, h * 0.90f),
            strokeWidth = s * 0.028f,
            cap = StrokeCap.Round,
        )
    }
    // Tail feathers.
    drawPath(
        Path().apply {
            moveTo(w * 0.72f, h * 0.60f)
            lineTo(w * 0.94f, h * 0.40f)
            lineTo(w * 0.88f, h * 0.68f)
            close()
        },
        ChickenCream,
    )
    drawOval(
        color = ChickenCream,
        topLeft = Offset(w * 0.34f, h * 0.48f),
        size = Size(w * 0.42f, h * 0.32f),
    )
    drawCircle(ChickenCream, s * 0.14f, Offset(w * 0.36f, h * 0.40f))
    // Comb.
    listOf(0.30f, 0.37f, 0.44f).forEach { fx ->
        drawCircle(EmberLight, s * 0.035f, Offset(w * fx, h * 0.28f))
    }
    // Beak.
    drawPath(
        Path().apply {
            moveTo(w * 0.24f, h * 0.40f)
            lineTo(w * 0.10f, h * 0.44f)
            lineTo(w * 0.24f, h * 0.47f)
            close()
        },
        RuneGold,
    )
    drawCircle(VaultNight, s * 0.028f, Offset(w * 0.33f, h * 0.38f))
}

// ===========================================================================================
// Boss
// ===========================================================================================

private fun DrawScope.drawBoss() {
    val w = size.width
    val h = size.height
    val s = size.minDimension

    // Menace aura.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(EmberRed.copy(alpha = 0.40f), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.52f),
            radius = s * 0.52f,
        ),
        radius = s * 0.52f,
        center = Offset(w * 0.5f, h * 0.52f),
    )

    // Hulking torso.
    drawPath(
        Path().apply {
            moveTo(w * 0.24f, h * 0.88f)
            lineTo(w * 0.32f, h * 0.54f)
            lineTo(w * 0.68f, h * 0.54f)
            lineTo(w * 0.76f, h * 0.88f)
            close()
        },
        VaultDeep,
    )

    // Horns, drawn BEFORE the head so their bases are hidden behind the skull and they read
    // as attached rather than as two triangles floating beside it.
    listOf(
        listOf(0.34f to 0.34f, 0.23f to 0.11f, 0.46f to 0.26f),   // left
        listOf(0.66f to 0.34f, 0.77f to 0.11f, 0.54f to 0.26f),   // right
    ).forEach { pts ->
        drawPath(
            Path().apply {
                moveTo(w * pts[0].first, h * pts[0].second)
                lineTo(w * pts[1].first, h * pts[1].second)
                lineTo(w * pts[2].first, h * pts[2].second)
                close()
            },
            RuneGold,
        )
    }

    // Head.
    drawCircle(VaultDeep, s * 0.18f, Offset(w * 0.5f, h * 0.40f))

    // Burning eyes.
    listOf(0.44f, 0.56f).forEach { fx ->
        drawCircle(EmberLight.copy(alpha = 0.45f), s * 0.055f, Offset(w * fx, h * 0.40f))
        drawCircle(EmberLight, s * 0.028f, Offset(w * fx, h * 0.40f))
    }

    // Vault key slung across the chest - this one guards a vault, after all.
    drawLine(
        color = RuneGold,
        start = Offset(w * 0.40f, h * 0.76f),
        end = Offset(w * 0.62f, h * 0.62f),
        strokeWidth = s * 0.035f,
        cap = StrokeCap.Round,
    )
    drawCircle(RuneGold, s * 0.05f, Offset(w * 0.37f, h * 0.79f))
    drawCircle(VaultDeep, s * 0.022f, Offset(w * 0.37f, h * 0.79f))
}
