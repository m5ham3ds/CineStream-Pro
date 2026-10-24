package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.example.R

internal enum class PosterState {
    LOADING,
    SUCCESS,
    ERROR
}

@Composable
internal fun MediaCardPoster(
    posterUrl: String,
    mediaId: String?,
    title: String,
    modifier: Modifier = Modifier
) {
    var posterState by remember(posterUrl) {
        mutableStateOf(if (posterUrl.isBlank()) PosterState.ERROR else PosterState.LOADING)
    }

    Box(modifier = modifier) {
        if (posterState == PosterState.LOADING) {
            CardShimmer(modifier = Modifier.fillMaxSize())
        } else if (posterState == PosterState.ERROR) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        if (posterUrl.isNotBlank()) {
            val context = LocalContext.current
            val imageRequest = remember(posterUrl, mediaId) {
                val model = if (!mediaId.isNullOrBlank()) {
                    com.example.utils.DownloadedPostersManager.getPosterModel(context, mediaId, posterUrl)
                } else {
                    posterUrl
                }
                ImageRequest.Builder(context)
                    .data(model)
                    .memoryCacheKey(posterUrl)
                    .diskCacheKey(posterUrl)
                    .transitionFactory(com.example.utils.SelectiveCrossfadeTransitionFactory(100))
                    .scale(Scale.FILL)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onLoading = {
                    posterState = PosterState.LOADING
                },
                onSuccess = {
                    posterState = PosterState.SUCCESS
                },
                onError = {
                    posterState = PosterState.ERROR
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    title: String,
    posterUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    rank: Int? = null,
    rating: Double? = null,
    year: String? = null,
    isMovie: Boolean = true,
    contentType: String = if (isMovie) com.example.data.model.ContentType.MOVIE else com.example.data.model.ContentType.TV,
    mediaId: String? = null,
    isBookmarked: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "media-card-press-scale"
    )

    Box(
        modifier = modifier
            .width(140.dp)
            .aspectRatio(3f / 4f)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        MediaCardPoster(
            posterUrl = posterUrl,
            mediaId = mediaId,
            title = title,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlay for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.9f)),
                        startY = 150f
                    )
                )
        )

        // Top Badges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            if (rank != null) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = rank.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(modifier = Modifier.width(4.dp))
            }

            if (title.isNotBlank()) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, 
                    contentDescription = stringResource(R.string.cd_bookmark), 
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Bottom Text (Title, Rating, Year)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val displayRating = rating?.takeIf { it.isFinite() && it > 0.0 }
                val displayYear = year?.trim()?.takeIf { value ->
                    value.isNotEmpty() && value != "0" && value != "0000"
                }
                if (displayRating != null || displayYear != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (displayRating != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = stringResource(R.string.cd_rating),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = displayRating.toString(),
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (displayYear != null) {
                            Text(
                                text = displayYear,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )
            }
        }
    }
}
