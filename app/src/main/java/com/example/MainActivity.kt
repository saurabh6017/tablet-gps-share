package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.NavigationRoot
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.LocalNetGpsViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: LocalNetGpsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiConfig by viewModel.uiConfig.collectAsStateWithLifecycle()

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    hasLocationPermission()
                viewModel.setPermissionsGranted(fineGranted)
            }

            LaunchedEffect(Unit) {
                val alreadyGranted = hasLocationPermission()
                viewModel.setPermissionsGranted(alreadyGranted)
                if (!alreadyGranted) {
                    permissionLauncher.launch(buildRequiredPermissionsArray())
                }
            }

            MyApplicationTheme(darkTheme = uiConfig.darkTheme) {
                NavigationRoot(
                    viewModel = viewModel,
                    onRequestRuntimePermissions = {
                        permissionLauncher.launch(buildRequiredPermissionsArray())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setPermissionsGranted(hasLocationPermission())
        viewModel.refreshNetworkInterfaces()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildRequiredPermissionsArray(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }
}
