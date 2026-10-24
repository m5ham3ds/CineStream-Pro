package com.example.data.repository

import com.example.data.model.Report
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object ReportRepository {
    private const val COLLECTION_REPORTS = "reports"
    private val db get() = FirebaseFirestore.getInstance()

    suspend fun submitReport(
        contentId: String,
        contentType: String = "movie",
        reason: String = "broken_stream",
        details: String = "",
        title: String = "",
        type: String = "content"
    ): Result<String> {
        if (!UserSecurityManager.canRequest()) {
            val errorMsg = UserSecurityManager.getRequestRestrictionMessage()
            return Result.failure(SecurityException(errorMsg))
        }
        val currentUser = FirebaseAuth.getInstance().currentUser
            ?: return Result.failure(IllegalStateException("User must be signed in to submit a report"))

        return try {
            val docRef = db.collection(COLLECTION_REPORTS).document()
            val finalTitle = title.ifBlank { "Report: $reason" }
            val finalDetails = details.ifBlank { reason }
            val report = Report(
                id = docRef.id,
                reportId = docRef.id,
                userId = currentUser.uid,
                userEmail = currentUser.email ?: "",
                type = type,
                targetType = contentType,
                targetId = contentId,
                title = finalTitle,
                description = finalDetails,
                status = "PENDING",
                createdAt = System.currentTimeMillis(),
                reason = reason,
                details = finalDetails,
                contentId = contentId,
                contentType = contentType
            )
            docRef.set(report.toCreationFirestoreMap()).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserReports(): List<Report> {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return emptyList()
        return try {
            val snapshot = db.collection(COLLECTION_REPORTS)
                .whereEqualTo("userId", currentUser.uid)
                .get()
                .await()
            snapshot.documents.map { Report.fromDocument(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
