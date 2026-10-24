package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watched_episodes")
data class WatchedEpisode(
    @PrimaryKey val id: String = ""
) {
    // Secondary no-argument constructor strictly required by Firebase Firestore CustomClassMapper
    constructor() : this("")
}
