package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * Expandable synopsis component implementing Phase 23.2 Phase F requirements:
 * - Shows an untruncated preview limited to [collapsedMaxLines] lines by default.
 * - Detects visual overflow dynamically. Short texts that fit inside the limit show no toggle.
 * - Clicking "Show more" ("عرض المزيد") expands the complete text smoothly.
 * - Clicking "Show less" ("عرض أقل") collapses the text back to preview lines.
 * - Preserves line breaks, paragraph spacing, and untruncated text content without substring surgery.
 * - Resets expansion state when overview text changes.
 */
@Composable
fun ExpandableSynopsis(
    overview: String,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 3
) {
    if (overview.isBlank()) {
        Text(
            text = stringResource(R.string.no_overview_available),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
        return
    }

    var isExpanded by remember(overview) { mutableStateOf(false) }
    var canExpand by remember(overview) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 200))
    ) {
        Text(
            text = overview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (isExpanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayoutResult ->
                if (!isExpanded) {
                    if (textLayoutResult.hasVisualOverflow || textLayoutResult.lineCount > collapsedMaxLines) {
                        canExpand = true
                    }
                }
            }
        )

        if (canExpand || isExpanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExpanded) stringResource(R.string.show_less) else stringResource(R.string.show_more),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
