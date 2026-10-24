package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Extension for LazyListScope to render a virtualized grid where each row is an individual LazyColumn item.
 * This ensures that offscreen rows/cards are recycled and never composed simultaneously,
 * eliminating jank and high RAM usage in large lists.
 */
fun <T> LazyListScope.verticalGridItems(
    items: List<T>,
    columns: Int = 3,
    horizontalSpacing: Dp = 16.dp,
    verticalSpacing: Dp = 16.dp,
    horizontalPadding: Dp = 16.dp,
    key: ((T) -> Any)? = null,
    itemContent: @Composable (T) -> Unit
) {
    val safeItems = if (key != null) items.distinctBy { key(it) } else items
    val chunked = safeItems.chunked(columns)
    items(
        count = chunked.size,
        key = if (key != null) { index ->
            val row = chunked[index]
            "grid_row_${index}_" + row.joinToString(separator = "_") { key(it).toString() }
        } else null
    ) { rowIndex ->
        val rowItems = chunked[rowIndex]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(bottom = verticalSpacing),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
        ) {
            for (colIndex in 0 until columns) {
                if (colIndex < rowItems.size) {
                    Box(modifier = Modifier.weight(1f)) {
                        itemContent(rowItems[colIndex])
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Fallback VerticalGrid for small fixed layouts or non-lazy scopes.
 */
@Composable
fun <T> VerticalGrid(
    items: List<T>,
    columns: Int = 3,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        for (i in items.indices step columns) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                for (j in 0 until columns) {
                    if (i + j < items.size) {
                        Box(modifier = Modifier.weight(1f)) {
                            content(items[i + j])
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
