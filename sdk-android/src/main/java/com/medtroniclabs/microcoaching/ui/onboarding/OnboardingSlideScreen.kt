package com.medtroniclabs.microcoaching.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.OnboardingDotIndicator

/**
 * Three-slide onboarding carousel.
 *
 * Layout matches the Container-2/3/4 designs:
 *   - "X of Y" counter anchored top-right
 *   - Illustration emoji in a light rounded card, centred vertically
 *   - Title + body below the illustration
 *   - Dot indicator + "Next →" / "Get started →" button + "Skip intro" link at the bottom
 *
 * @param uiState Current state from [OnboardingViewModel].
 * @param onNext  Called when "Next" is tapped (ViewModel advances the index).
 * @param onSkip  Called when "Skip intro" is tapped.
 * @param onDone  Called when "Get started" is tapped on the last slide.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingSlideScreen(
    uiState: OnboardingUiState,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
) {
    val slides = OnboardingSlideData.slidesFor(MicroCoachingSDK.getInstance().config.language)
    val initialPage = (uiState as? OnboardingUiState.Slides)?.currentIndex ?: 0
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { slides.size },
    )

    // Keep pagerState in sync with ViewModel on external advance (e.g. "Next" button)
    LaunchedEffect(uiState) {
        val idx = (uiState as? OnboardingUiState.Slides)?.currentIndex ?: return@LaunchedEffect
        if (idx != pagerState.currentPage) {
            pagerState.animateScrollToPage(idx)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F8F8)),
    ) {
        // ── "X of Y" counter — top right ─────────────────────────────────────
        Text(
            text = stringResource(R.string.onboarding_page_counter, pagerState.currentPage + 1, slides.size),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF888888),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 24.dp),
        )

        // ── Slide pager — fills centre area ──────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(bottom = 180.dp), // leave room for bottom controls
        ) { page ->
            SlideContent(slide = slides[page])
        }

        // ── Bottom controls ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingDotIndicator(
                pageCount = slides.size,
                currentPage = pagerState.currentPage,
            )

            Spacer(Modifier.height(20.dp))

            val isLastPage = pagerState.currentPage == slides.lastIndex
            Button(
                onClick = if (isLastPage) onDone else onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (isLastPage) stringResource(R.string.onboarding_get_started) else stringResource(R.string.onboarding_next),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onSkip) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    color = Color(0xFF888888),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

// ── Single slide content ──────────────────────────────────────────────────────

@Composable
private fun SlideContent(slide: OnboardingSlide) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Illustration card — light grey rounded box
        Box(
            modifier = Modifier
                .size(160.dp)
                .background(
                    color = Color(0xFFF0F0F0),
                    shape = RoundedCornerShape(24.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = slide.illustrationEmoji,
                fontSize = 72.sp,
            )
        }

        Spacer(Modifier.height(36.dp))

        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
            ),
            textAlign = TextAlign.Center,
            color = Color(0xFF1A1A1A),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = slide.body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color(0xFF666666),
            lineHeight = 22.sp,
        )
    }
}
