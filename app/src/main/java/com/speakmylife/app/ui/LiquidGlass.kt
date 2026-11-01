package com.speakmylife.app.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speakmylife.app.ui.theme.AppPalette
import com.speakmylife.app.ui.theme.BrandDayPalette
import com.speakmylife.app.ui.theme.BrandForest
import com.speakmylife.app.ui.theme.BrandForestDark
import com.speakmylife.app.ui.theme.BrandGold
import com.speakmylife.app.ui.theme.BrandNightPalette
import com.speakmylife.app.ui.theme.HighContrastPalette
import com.speakmylife.app.ui.theme.isLight
import com.speakmylife.app.ui.theme.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Liquid Glass（液態玻璃，對齊 iOS 26 的設計語言、與網頁版同一套參數）。
 *
 * 只用在「浮在內容上面的東西」：頂部標題列、底部導覽列、導覽選中指示器、對話框、
 * 語意確認卡、「開始說話」膠囊。內容卡片維持實色——整個畫面都是玻璃會讓字浮在
 * 一片模糊上，對視力退化的長輩是災難。
 *
 * Compose 沒有「模糊背後內容」的能力（Modifier.blur 只模糊自己），也不加 Haze 之類依賴，
 * 所以玻璃感由四件事疊出來：
 *   1. 半透明表面（淺色＝暖白、深色＝煙燻深綠，alpha 0.8 上下——比 iOS 不透明，
 *      為了玻璃上的字在任何底下都有 ≥ 4.5:1）；
 *   2. 內側高光：上半部一層白色漸層，像光從上面打進玻璃；
 *   3. 細高光描邊：上亮下暗的 1dp 漸層邊，玻璃的「厚度」就靠這條線；
 *   4. 後面放 [LiquidAmbientBackdrop]：緩慢漂移的品牌色光暈，讓透明有東西可以透。
 *
 * 無障礙優先序：高對比 > 減少動態 > 好看。
 *   · 高對比：玻璃一律換成不透明實色＋ 2dp 實線框，光暈不畫。
 *   · 減少動態（系統動畫比例 = 0）：光暈不漂移、指示器直接跳位、圖示不跳。
 */

/** 是不是高對比模式（玻璃要退成實色）。 */
internal val AppPalette.isHighContrast: Boolean get() = this == HighContrastPalette

private val AppPalette.isBrand: Boolean get() = this == BrandDayPalette || this == BrandNightPalette

/**
 * 玻璃的底色。淺色主題用卡片色（暖白）、深色主題用表面色（煙燻）＋ [opacity]。
 * 高對比回傳不透明的表面色。
 */
fun glassFill(palette: AppPalette, opacity: Float = 0.8f): Color =
    if (palette.isHighContrast) palette.surface
    else palette.surface.copy(alpha = opacity.coerceIn(0f, 1f))

/** 內側高光：上半部從白漸到透明。 */
private fun glassSheen(light: Boolean): Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = if (light) 0.45f else 0.10f),
    0.55f to Color.White.copy(alpha = 0f),
)

/** 高光描邊：上緣亮（光打到的那一面）、下緣暗（玻璃的底邊）。 */
private fun glassRim(palette: AppPalette, bottomTint: Color? = null): Brush {
    val light = palette.isLight
    val top = Color.White.copy(alpha = if (light) 0.95f else 0.24f)
    val bottom = bottomTint ?: if (light) palette.textLight.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f)
    return Brush.verticalGradient(listOf(top, bottom))
}

private fun glassShadowTint(palette: AppPalette): Color =
    if (palette.isLight) BrandForestDark.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.55f)

/**
 * 只畫玻璃的「光」：內側高光＋高光描邊，不畫底色。給自己有底色的元件用
 * （例如 AlertDialog 的 Surface——底色交給 containerColor = [glassFill]）。
 * 高對比：只畫 2dp 實線框。
 */
fun Modifier.glassDecor(
    palette: AppPalette,
    shape: Shape,
    hcBorder: Color = palette.textMuted,
    rimBottomTint: Color? = null,
): Modifier =
    if (palette.isHighContrast) this.border(2.dp, hcBorder, shape)
    else this
        .background(glassSheen(palette.isLight), shape)
        .border(if (rimBottomTint != null) 1.5.dp else 1.dp, glassRim(palette, rimBottomTint), shape)

/**
 * 液態玻璃表面（浮起來的卡片／膠囊／對話框用）。
 *
 * @param opacity 底色不透明度。浮在可控背景（光暈）上可以低一點；會蓋在任意內容上的請 ≥ 0.84。
 * @param tint 帶色玻璃（例如語意確認卡帶一點主色）；描邊下緣也會換成這個顏色。
 * @param fill 直接指定底色（例如暖黃膠囊）；給了就不用 [opacity]／[tint]。
 * @param elevation 柔和陰影。半透明表面的陰影會從玻璃透出一點，所以保持小（≤ 8dp）。
 */
fun Modifier.liquidGlass(
    palette: AppPalette,
    shape: Shape,
    opacity: Float = 0.8f,
    tint: Color? = null,
    tintAlpha: Float = 0.10f,
    fill: Color? = null,
    elevation: Dp = 0.dp,
    hcBorder: Color = palette.textMuted,
): Modifier {
    if (palette.isHighContrast) {
        return this.clip(shape).background(palette.surface).border(2.dp, hcBorder, shape)
    }
    val shadowTint = glassShadowTint(palette)
    return this
        .then(
            if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false, ambientColor = shadowTint, spotColor = shadowTint)
            else Modifier
        )
        .clip(shape)
        .background(fill ?: glassFill(palette, opacity))
        .then(if (fill == null && tint != null) Modifier.background(tint.copy(alpha = tintAlpha)) else Modifier)
        .glassDecor(palette, shape, rimBottomTint = tint?.copy(alpha = 0.45f))
}

/** 整條貼邊的玻璃列（標題列、導覽列、導覽軌）的高光描邊畫在哪一邊。 */
enum class GlassEdge { Top, Bottom, End }

/**
 * 貼邊玻璃列：不裁形狀、不畫陰影（整條寬的陰影會在半透明面上透出髒髒的一片），
 * 只在接觸內容的那一邊畫一條高光線，另一邊靠內側高光。
 * 高對比：不透明表面色＋那一邊 2dp 實線。
 */
fun Modifier.liquidGlassBar(
    palette: AppPalette,
    opacity: Float = 0.84f,
    edge: GlassEdge,
): Modifier = this.drawWithCache {
    val hc = palette.isHighContrast
    val light = palette.isLight
    val fill = glassFill(palette, opacity)
    val sheen = if (edge == GlassEdge.Bottom) {
        // 標題列：光從上面來，上亮下透
        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (light) 0.30f else 0.06f), Color.Transparent))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (light) 0.36f else 0.08f), Color.Transparent), endY = size.height * 0.6f)
    }
    val lineW = if (hc) 2.dp.toPx() else 1.dp.toPx()
    val lineColor = when {
        hc -> palette.textMuted
        light -> Color.White.copy(alpha = 0.95f)
        else -> Color.White.copy(alpha = 0.16f)
    }
    // 淺色玻璃的白線在暖米底上幾乎看不見，外側再壓一條很淡的墨色線，邊界才分得出來
    val shade = if (light) palette.textLight.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.35f)
    onDrawBehind {
        drawRect(fill)
        if (!hc) drawRect(sheen)
        when (edge) {
            GlassEdge.Top -> {
                if (!hc) drawRect(shade, Offset.Zero, Size(size.width, 1.dp.toPx()))
                drawRect(lineColor, Offset(0f, if (hc) 0f else 1.dp.toPx()), Size(size.width, lineW))
            }
            GlassEdge.Bottom -> {
                drawRect(lineColor, Offset(0f, size.height - lineW - if (hc) 0f else 1.dp.toPx()), Size(size.width, lineW))
                if (!hc) drawRect(shade, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            }
            GlassEdge.End -> {
                drawRect(lineColor, Offset(size.width - lineW - if (hc) 0f else 1.dp.toPx(), 0f), Size(lineW, size.height))
                if (!hc) drawRect(shade, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
            }
        }
    }
}

// ─── 背後的光暈 ─────────────────────────────────────────────────────────

/**
 * 緩慢漂移的品牌色光暈（深綠＋暖黃＋一點葉綠），放在整個 App 的最底層，
 * 玻璃標題列與導覽列透出來的就是它。
 *
 * 效能：每顆光暈只畫一次（Canvas 的徑向漸層），漂移完全靠 graphicsLayer 的 translation——
 * 動畫值只在 layer 階段被讀取，每幀不重組、也不重畫。API 31+ 再對光暈「自己」加模糊讓邊緣更柔，
 * 舊版直接用漸層本身的柔邊。
 *
 * 亮度刻意很低（淺色 ≤ 0.12）：這層會從沒有卡片的地方透出來，次要文字直接壓在上面也要有 4.5:1。
 * 高對比不畫；減少動態時停在固定位置。
 */
@Composable
fun LiquidAmbientBackdrop(palette: AppPalette, modifier: Modifier = Modifier) {
    if (palette.isHighContrast) return
    val reduce = rememberReduceMotion()
    val light = palette.isLight
    val primary = if (palette.isBrand) (if (light) BrandForest else Color(0xFF3E9C82)) else palette.neonCyan
    val warm = if (palette.isBrand) BrandGold else palette.auraAmber
    Box(modifier) {
        // 右上：主色，給標題列透
        GlowBlob(primary, if (light) 0.10f else 0.20f, 0.88f, 0.06f, 0.75f, 36.dp, 28.dp, 21000, !reduce)
        // 左中：暖黃
        GlowBlob(warm, if (light) 0.12f else 0.12f, 0.08f, 0.48f, 0.65f, 30.dp, 40.dp, 26000, !reduce, phaseOffset = 0.33f)
        // 底部：葉綠＋主色，給導覽列透
        GlowBlob(palette.neonGreen, if (light) 0.08f else 0.14f, 0.62f, 0.98f, 0.70f, 44.dp, 20.dp, 23000, !reduce, phaseOffset = 0.66f)
    }
}

/**
 * 一顆光暈。[anchorX]/[anchorY] 是中心（佔父層寬高的比例），[radiusFrac] 是半徑（佔父層寬的比例），
 * 在 ±[dx]、±[dy] 範圍內沿著一個橢圓慢慢繞（一圈 [periodMs]）。
 */
@Composable
internal fun BoxScope.GlowBlob(
    color: Color,
    alpha: Float,
    anchorX: Float,
    anchorY: Float,
    radiusFrac: Float,
    dx: Dp,
    dy: Dp,
    periodMs: Int,
    animate: Boolean,
    phaseOffset: Float = 0f,
    blurRadius: Dp = 28.dp,
) {
    // animate 在這個畫面的一生中不會變（減少動態是開 App 時讀一次），
    // 所以條件式呼叫 composable 是安全的；關掉時就完全不跑無限動畫、不送幀。
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
            label = "glowPhase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(blurRadius, BlurredEdgeTreatment.Unbounded)
    } else Modifier
    Canvas(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer {
                val a = ((phase.value + phaseOffset) % 1f) * 2f * PI.toFloat()
                translationX = sin(a) * dx.toPx()
                translationY = cos(a) * dy.toPx()
            }
            .then(blur)
    ) {
        val r = size.width * radiusFrac
        if (r <= 0f) return@Canvas
        val c = Offset(size.width * anchorX, size.height * anchorY)
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha),
                0.55f to color.copy(alpha = alpha * 0.45f),
                1f to color.copy(alpha = 0f),
                center = c,
                radius = r
            ),
            radius = r,
            center = c
        )
    }
}

// ─── 靈動：彈簧按壓、跳動的導覽圖示、液態指示器 ─────────────────────────

/**
 * 按下縮到 96%、放開用低回彈彈簧回來（dampingRatio 0.6）。
 * 搭配元件自己的 interactionSource 使用。減少動態時不縮放。
 */
@Composable
fun Modifier.pressBounce(source: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val reduce = rememberReduceMotion()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) pressedScale else 1f,
        animationSpec = if (pressed) tween(140) else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "pressBounce"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 導覽圖示：被選中的那一刻輕輕往上跳 5dp 再彈回（dampingRatio 0.55），並放大到 112%。
 * 第一次出現時不跳（開 App 時目前分頁不該自己跳一下）。減少動態時完全靜止。
 */
@Composable
fun BouncyNavIcon(
    icon: ImageVector,
    selected: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val reduce = rememberReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (selected && !reduce) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "navIconScale"
    )
    val hop = remember { Animatable(0f) }
    val initial = remember { booleanArrayOf(true) }
    LaunchedEffect(selected) {
        if (initial[0]) { initial[0] = false; return@LaunchedEffect }
        if (selected && !reduce) {
            hop.animateTo(-5f, tween(110, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
        } else {
            hop.snapTo(0f)
        }
    }
    Icon(
        icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = hop.value * density
        }
    )
}

/** 底部導覽的一項。 */
data class GlassNavItem(val icon: ImageVector, val label: String)

/**
 * 液態玻璃底部導覽列。取代 Material NavigationBar，因為要自己畫「液體」指示器：
 *
 * 指示器的左右兩緣各是一個彈簧。往右換頁時右緣用快彈簧先衝過去、左緣用慢彈簧跟上，
 * 中間那一下指示器被拉長、上下略微變薄，到位後兩緣各自帶一點回彈——像一滴水被拖過去。
 * 動畫值只在 drawBehind 裡讀，每幀只重畫指示器那一層，不重組整列。
 *
 * @param selectedIndex 目前分頁；-1 表示在子頁（指示器淡出）。
 * @param showLabels 字大到放不下時傳 false，只留圖示（圖示改帶 contentDescription）。
 */
@Composable
fun LiquidGlassNavBar(
    palette: AppPalette,
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    showLabels: Boolean,
    modifier: Modifier = Modifier,
    opacity: Float = 0.8f,
) {
    val reduce = rememberReduceMotion()
    val hc = palette.isHighContrast
    val light = palette.isLight
    val start = selectedIndex.coerceAtLeast(0).toFloat()
    val left = remember { Animatable(start) }
    val right = remember { Animatable(start + 1f) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val target = selectedIndex.toFloat()
        if (reduce || left.value == target) {
            left.snapTo(target)
            right.snapTo(target + 1f)
            return@LaunchedEffect
        }
        val movingRight = target > left.value
        val lead = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)
        val trail = spring<Float>(dampingRatio = 0.58f, stiffness = 200f)
        kotlinx.coroutines.coroutineScope {
            launch { left.animateTo(target, if (movingRight) trail else lead) }
            launch { right.animateTo(target + 1f, if (movingRight) lead else trail) }
        }
    }
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selectedIndex >= 0) 1f else 0f,
        animationSpec = tween(200),
        label = "navIndicatorAlpha"
    )

    // 選中色：淺色玻璃＋淡綠指示器上，原本的主色只有 ~4:1；往墨色壓一點拿到 ≥ 4.5:1。
    val selectedColor = when {
        hc -> palette.neonCyan
        light -> lerp(palette.neonCyan, palette.textLight, 0.35f)
        else -> palette.neonCyan
    }
    val unselectedColor = palette.textMuted
    val pillFill = when {
        hc -> palette.surface
        else -> palette.neonCyan.copy(alpha = if (light) 0.12f else 0.22f)
    }
    val pillSheen = Color.White.copy(alpha = if (light) 0.55f else 0.10f)
    val pillRimTop = if (hc) palette.neonCyan else Color.White.copy(alpha = if (light) 0.95f else 0.28f)
    val pillRimBottom = if (hc) palette.neonCyan else palette.neonCyan.copy(alpha = if (light) 0.30f else 0.35f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassBar(palette, opacity, GlassEdge.Top)
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .height(if (showLabels) 72.dp else 64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // 液態指示器
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val n = items.size.coerceAtLeast(1)
                    val slot = size.width / n
                    val inset = 4.dp.toPx()
                    val l = left.value * slot + inset
                    val r = right.value * slot - inset
                    val w = r - l
                    if (w <= 0f || indicatorAlpha <= 0f) return@drawBehind
                    // 被拉長多少（1 = 靜止），拉長時上下變薄一點，像液體被拉開
                    val stretch = ((right.value - left.value) - 1f).coerceIn(0f, 1f)
                    val squeeze = stretch * 6.dp.toPx()
                    val top = squeeze
                    val h = size.height - squeeze * 2f
                    val cr = CornerRadius(h / 2f, h / 2f)
                    val tl = Offset(l, top)
                    val sz = Size(w, h)
                    drawRoundRect(pillFill, tl, sz, cr, alpha = indicatorAlpha)
                    if (!hc) {
                        drawRoundRect(
                            Brush.verticalGradient(listOf(pillSheen, Color.Transparent), startY = top, endY = top + h * 0.6f),
                            tl, sz, cr, alpha = indicatorAlpha
                        )
                    }
                    val sw = if (hc) 2.dp.toPx() else 1.dp.toPx()
                    drawRoundRect(
                        Brush.verticalGradient(listOf(pillRimTop, pillRimBottom), startY = top, endY = top + h),
                        Offset(l + sw / 2f, top + sw / 2f),
                        Size(w - sw, h - sw),
                        CornerRadius((h - sw) / 2f, (h - sw) / 2f),
                        alpha = indicatorAlpha,
                        style = Stroke(width = sw)
                    )
                }
        )
        Row(
            modifier = Modifier.fillMaxSize().selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, item ->
                val selected = i == selectedIndex
                val source = remember { MutableInteractionSource() }
                val color = if (selected) selectedColor else unselectedColor
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(i) },
                            role = Role.Tab,
                            interactionSource = source,
                            indication = LocalIndication.current
                        )
                        .pressBounce(source, 0.92f)
                ) {
                    BouncyNavIcon(
                        icon = item.icon,
                        selected = selected,
                        contentDescription = if (showLabels) null else item.label,
                        tint = color,
                        modifier = Modifier.size(26.dp)
                    )
                    if (showLabels) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.label,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = color,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
