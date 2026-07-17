package com.aftglw.devapi.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A standardized primary button following Achat design tokens.
 */
@Composable
fun AchatPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(AchatTheme.tokens.buttonHeight),
        enabled = enabled,
        shape = AchatTheme.shapes.card,
        colors = ButtonDefaults.buttonColors(
            containerColor = AchatTheme.colors.primary,
            contentColor = if (AchatTheme.colors.themeId == "marathon") Color.Black else Color.White
        )
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AchatTheme.typography.title
        )
    }
}

/**
 * A standardized list item with appropriate "breathing room".
 */
@Composable
fun AchatListItem(
    title: String,
    subtitle: String? = null,
    icon: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = AchatTheme.colors.surface
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = AchatTheme.tokens.screenPadding, vertical = 16.dp)
                .defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(Modifier.size(24.dp)) { icon() }
                Spacer(Modifier.width(16.dp))
            }
            
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = AchatTheme.colors.onSurface,
                    fontFamily = AchatTheme.typography.title
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.6f),
                        fontFamily = AchatTheme.typography.body
                    )
                }
            }
            
            if (trailing != null) {
                Box { trailing() }
            }
        }
    }
}

/**
 * A standardized section header with large spacing.
 */
@Composable
fun AchatSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AchatTheme.tokens.screenPadding, vertical = 8.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
        color = AchatTheme.colors.primary.copy(alpha = 0.8f),
        letterSpacing = 1.5.sp,
        fontFamily = AchatTheme.typography.mono
    )
}

/**
 * Marathon-specific high-saturation background logic.
 */
fun Modifier.marathonBackground(): Modifier = this.then(
    Modifier.background(AchatTheme.colors.background)
)
