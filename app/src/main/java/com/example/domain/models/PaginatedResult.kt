package com.example.domain.models

import androidx.compose.runtime.Immutable

@Immutable
data class PaginatedResult<T>(
    val items: List<T>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int = 0
)
