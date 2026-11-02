package com.example.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.viewmodel.LocalNetGpsViewModel

/**
 * Entry-point composable wrapper delegating directly to [NavigationRoot].
 */
@Composable
fun LocalNetGpsScreen(
    viewModel: LocalNetGpsViewModel,
    onRequestRuntimePermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRoot(
        viewModel = viewModel,
        onRequestRuntimePermissions = onRequestRuntimePermissions,
        modifier = modifier
    )
}
