package com.dmytro.leo.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmytro.leo.R

/** The gold lion's head from the app icon. [tint] repaints it in one color. */
@Composable
fun LionEmblem(size: Dp, modifier: Modifier = Modifier, tint: Color? = null) {
    Image(
        painterResource(R.drawable.ic_lion_head),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = tint?.let { ColorFilter.tint(it) },
    )
}

/** Crimson banner with a thin gold edge at the bottom: the top bar of every screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BannerBar(
    title: String,
    showLion: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.background(Brush.verticalGradient(listOf(LionColors.CrimsonDark, LionColors.Crimson)))) {
        CompositionLocalProvider(LocalContentColor provides LionColors.GoldLight) {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showLion) {
                            LionEmblem(34.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            title,
                            fontFamily = Heraldic,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            letterSpacing = 1.5.sp,
                            color = LionColors.GoldLight,
                        )
                    }
                },
                navigationIcon = navigationIcon,
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    navigationIconContentColor = LionColors.GoldLight,
                    actionIconContentColor = LionColors.GoldLight,
                    titleContentColor = LionColors.GoldLight,
                ),
            )
        }
        GoldRule()
    }
}

/** A thin gold line that fades out at both ends. */
@Composable
fun GoldRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.5.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, LionColors.Gold, Color.Transparent)))
    )
}

/** Section heading in heraldic small caps with a gold rule under it. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .width(48.dp)
                .height(1.5.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        )
    }
}
