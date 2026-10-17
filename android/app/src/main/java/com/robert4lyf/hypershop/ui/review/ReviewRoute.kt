package com.robert4lyf.hypershop.ui.review

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.robert4lyf.hypershop.di.AppContainer

/** Placeholder until Step 5.4. */
@Composable
fun ReviewRoute(
    container: AppContainer,
    jobId: Long,
    snackbar: SnackbarHostState,
    onDone: () -> Unit,
) {
    Text("review $jobId")
}
