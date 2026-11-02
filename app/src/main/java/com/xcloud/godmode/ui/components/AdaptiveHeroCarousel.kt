package com.xcloud.godmode.ui.components

import android.content.pm.PackageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.xcloud.godmode.constants.XboxAssetsCodex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import kotlin.math.absoluteValue

/**
 * AdaptiveHeroCarousel: Unified Hero Carousel for Android TV, Tablet, and Mobile.
 *
 * 1. TV Engine: D-Pad navigation via FocusRequester, 1.06x scale on focus, glowing white border, 56dp overscan padding.
 * 2. Touch Engine: Fluid gesture snapping with HorizontalPager and dot indicator on mobile/tablets.
 * 3. Smart Auto-Play: Suspends automatically whenever the user interacts with D-Pad or touches the screen.
 * 4. Focus Trap Prevention: Clean traversal to surrounding content.
 */

data class CarouselFeaturedItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val backdropUrl: String,
    val rating: String = "4K HDR",
    val isGamePass: Boolean = true,
    val isOwned: Boolean = false,
    val capabilities: List<String> = emptyList()
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdaptiveHeroCarousel(
    items: List<CarouselFeaturedItem>,
    modifier: Modifier = Modifier,
    autoScrollDelayMs: Long = 6000L,
    onItemClick: (CarouselFeaturedItem) -> Unit
) {
    if (items.isEmpty()) return

    val context = LocalContext.current
    val isTv = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { items.size })
    var isUserInteracting by remember { mutableStateOf(false) }

    // Auto-scroll loop: active only when user is not directly interacting
    LaunchedEffect(isUserInteracting, items.size) {
        if (!isUserInteracting && items.size > 1) {
            while (true) {
                delay(autoScrollDelayMs)
                val nextPage = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(
                    page = nextPage,
                    animationSpec = tween(durationMillis = 800)
                )
            }
        }
    }

    val carouselHeight: Dp = if (isTv) 340.dp else 220.dp
    val horizontalPadding: Dp = if (isTv) 56.dp else 20.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(carouselHeight)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            pageSpacing = if (isTv) 24.dp else 12.dp,
            userScrollEnabled = !isTv
        ) { pageIndex ->
            val currentItem = items[pageIndex]

            val pageOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
            val targetScale = lerp(
                start = 0.90f,
                stop = 1.0f,
                fraction = 1f - pageOffset.absoluteValue.coerceIn(0f, 1f)
            )

            CarouselPageItem(
                item = currentItem,
                pageIndex = pageIndex,
                pagerState = pagerState,
                isTv = isTv,
                baseScale = targetScale,
                onFocusGained = {
                    isUserInteracting = true
                    if (isTv && pagerState.currentPage != pageIndex) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(
                                page = pageIndex,
                                animationSpec = tween(400)
                            )
                        }
                    }
                },
                onFocusLost = {
                    isUserInteracting = false
                },
                onClick = { onItemClick(currentItem) }
            )
        }

        // Dots Indicator for mobile and tablets
        if (!isTv && items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(items.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) Color(0xFF107C10) else Color.White.copy(alpha = 0.4f)
                            )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarouselPageItem(
    item: CarouselFeaturedItem,
    pageIndex: Int,
    pagerState: PagerState,
    isTv: Boolean,
    baseScale: Float,
    onFocusGained: () -> Unit,
    onFocusLost: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isFocused) {
        if (isFocused) onFocusGained() else onFocusLost()
    }

    val animatedScale by animateFloatAsState(
        targetValue = if (isTv && isFocused) 1.06f else baseScale,
        animationSpec = tween(durationMillis = 250),
        label = "hero_scale"
    )

    val focusBorder = if (isTv && isFocused) {
        BorderStroke(3.dp, Color(0xFF4ADE80))
    } else {
        BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .shadow(
                elevation = if (isFocused) 16.dp else 4.dp,
                shape = RoundedCornerShape(14.dp)
            )
            .border(focusBorder, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF161920))
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    onFocusGained()
                } else {
                    onFocusLost()
                }
            }
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = interactionSource, indication = null) {
                onClick()
            }
    ) {
        AsyncImage(
            model = item.backdropUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Contrast gradient for perfect readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.95f)
                        ),
                        startY = 60f
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(if (isTv) 24.dp else 16.dp)
        ) {
            Row(
                modifier = Modifier.wrapContentWidth().background(Color.Transparent),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.category.isNotBlank()) {
                    Surface(
                        color = Color.Transparent, // Strictly transparent as requested
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            text = item.category.uppercase(),
                            color = Color(0xFF107C10), // Move green to text to avoid "bar" artifact
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                if (item.rating.isNotBlank()) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.5f)),
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            text = item.rating,
                            color = Color(0xFF4ADE80),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Official Badges
                if (item.isGamePass) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(22.dp).wrapContentWidth()
                    ) {
                        Icon(
                            imageVector = XboxUnifiedIcons.GamePass,
                            contentDescription = "Game Pass",
                            tint = Color(0xFF107C10),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).fillMaxHeight()
                        )
                    }
                }
                
                // icon-container (Adaptive Render - width: max-content, background: transparent)
                val hasController = item.capabilities.any { it.contains("Mando", true) || it.contains("Controller", true) || it.contains("Gamepad", true) }
                val hasTouch = item.capabilities.any { it.contains("Táctil", true) || it.contains("Touch", true) }
                val hasMkb = item.capabilities.any { it.contains("Teclado", true) || it.contains("Mouse", true) || it.contains("Mkb", true) }
                
                if (hasController || hasTouch || hasMkb) {
                    Row(
                        modifier = Modifier
                            .wrapContentWidth()
                            .background(Color.Transparent) 
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasController) {
                            Icon(
                                imageVector = XboxUnifiedIcons.Controller,
                                contentDescription = "Control",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        
                        if (hasTouch) {
                            Icon(
                                imageVector = XboxUnifiedIcons.Touch,
                                contentDescription = "Táctil",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        
                        if (hasMkb) {
                            Icon(
                                imageVector = XboxUnifiedIcons.Keyboard,
                                contentDescription = "Teclado y Mouse",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (item.capabilities.any { it.contains("Series", ignoreCase = true) }) {
                    Surface(
                        color = Color.Transparent, 
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(22.dp).wrapContentWidth().border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    ) {
                        AsyncImage(
                            model = XboxAssetsCodex.SystemIcons.SERIES_X_S,
                            contentDescription = "Series X|S",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp).fillMaxHeight(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                if (item.isOwned) {
                    Icon(
                        imageVector = XboxUnifiedIcons.Owned,
                        contentDescription = "Propiedad",
                        tint = Color(0xFF4ADE80),
                        modifier = Modifier.size(24.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape).padding(3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.title,
                style = if (isTv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1
            )

            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(if (isTv) 0.75f else 0.92f)
            )
        }
    }
}
