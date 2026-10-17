package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.robert4lyf.hypershop.di.AppContainer

/** Placeholder until its own step. */
@Composable
fun RecipesRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onReview: (Long) -> Unit,
) {
    Text("recipes")
}
